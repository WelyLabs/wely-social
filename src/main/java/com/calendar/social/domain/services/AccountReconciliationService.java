package com.calendar.social.domain.services;

import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.domain.models.ReconciliationReport;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.DeletedUsersSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Re-applies the erasure to every wely-users tombstone not erased here yet, or deleted within the recent window. */
public class AccountReconciliationService {

    // JDK logger: the domain takes no logging dependency.
    private static final System.Logger LOG = System.getLogger(AccountReconciliationService.class.getName());

    private final DeletedUsersSource source;
    private final DeletedUserMarkers markers;
    private final AccountErasureService erasure;
    private final Clock clock;
    private final int pageSize;
    private final Duration recentWindow;

    public AccountReconciliationService(DeletedUsersSource source, DeletedUserMarkers markers,
                                        AccountErasureService erasure, Clock clock, int pageSize,
                                        Duration recentWindow) {
        this.source = source;
        this.markers = markers;
        this.erasure = erasure;
        this.clock = clock;
        this.pageSize = pageSize;
        this.recentWindow = recentWindow;
    }

    /** One pass over every tombstone; a failed erasure is counted and the pass goes on, a failed read fails it. */
    public Mono<ReconciliationReport> reconcile() {
        return Mono.defer(() -> {
            Instant recentSince = clock.instant().minus(recentWindow);
            // Each step emits only once its page is reconciled, so a failed read never cancels an erasure.
            return reconcilePageAfter(null, recentSince)
                    .expand(step -> step.next() == null ? Mono.empty() : reconcilePageAfter(step.next(), recentSince))
                    .map(Step::report)
                    .reduce(ReconciliationReport.NONE, ReconciliationReport::plus);
        });
    }

    private Mono<Step> reconcilePageAfter(String after, Instant recentSince) {
        return source.page(after, pageSize)
                .flatMap(page -> reconcilePage(page, recentSince)
                        .map(report -> new Step(report, nextCursor(after, page))));
    }

    // An empty page or a cursor that does not move ends the pass, whatever next says.
    private static String nextCursor(String after, DeletedUsersPage page) {
        return page.items().isEmpty() || Objects.equals(page.next(), after) ? null : page.next();
    }

    private Mono<ReconciliationReport> reconcilePage(DeletedUsersPage page, Instant recentSince) {
        List<DeletedUser> tombstones = page.items();
        return markers.erasedAmong(tombstones.stream().map(DeletedUser::userId).toList())
                .flatMapMany(erased -> Flux.fromIterable(tombstones)
                        .filter(tombstone -> isDue(tombstone, erased, recentSince)))
                .concatMap(this::erase)
                .reduce(new ReconciliationReport(tombstones.size(), 0, 0), ReconciliationReport::plus);
    }

    /** Due when never erased here, or deleted recently enough for a write from a still-valid token to follow. */
    private static boolean isDue(DeletedUser tombstone, Set<String> erased, Instant recentSince) {
        return !erased.contains(tombstone.userId())
                || tombstone.deletedAt() == null
                || !tombstone.deletedAt().isBefore(recentSince);
    }

    private Mono<ReconciliationReport> erase(DeletedUser tombstone) {
        return Mono.defer(() -> erasure.erase(new UserDeletedEventDTO(tombstone.userId(), tombstone.deletedAt())))
                .thenReturn(new ReconciliationReport(0, 1, 0))
                .onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Reconciliation could not erase userId={0}: {1}",
                            tombstone.userId(), error.toString());
                    return Mono.just(new ReconciliationReport(0, 0, 1));
                });
    }

    /** One reconciled page and the cursor of the page after it. */
    private record Step(ReconciliationReport report, String next) {
    }
}
