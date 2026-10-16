package com.calendar.social.domain.services;

import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.domain.models.ReconciliationReport;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.DeletedUsersSource;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Duration RECENT_WINDOW = Duration.ofHours(48);
    private static final int PAGE_SIZE = 2;
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(30));
    private static final Instant RECENTLY = NOW.minus(Duration.ofHours(47));
    private static final Duration ASYNC_ERASURE = Duration.ofMillis(50);

    @Mock
    private DeletedUsersSource source;

    @Mock
    private DeletedUserMarkers markers;

    @Mock
    private AccountErasureService erasure;

    private AccountReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new AccountReconciliationService(source, markers, erasure, Clock.fixed(NOW, ZoneOffset.UTC),
                PAGE_SIZE, RECENT_WINDOW);
    }

    private static DeletedUsersPage page(String next, DeletedUser... items) {
        return new DeletedUsersPage(List.of(items), next);
    }

    private void erasureSucceeds() {
        when(erasure.erase(any())).thenReturn(Mono.empty());
    }

    @Test
    void reconcile_shouldEraseATombstone_whenItHasNoErasedMarker() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u1", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1"))).thenReturn(Mono.just(Set.of()));
        erasureSucceeds();

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(1, 1, 0))
                .verifyComplete();

        verify(erasure).erase(new UserDeletedEventDTO("u1", LONG_AGO));
    }

    @Test
    void reconcile_shouldReapplyTheErasure_whenTheTombstoneIsUnderTheRecentWindow() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null,
                new DeletedUser("u1", RECENTLY), new DeletedUser("u2", NOW.minus(RECENT_WINDOW)))));
        when(markers.erasedAmong(List.of("u1", "u2"))).thenReturn(Mono.just(Set.of("u1", "u2")));
        erasureSucceeds();

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(2, 2, 0))
                .verifyComplete();

        verify(erasure).erase(new UserDeletedEventDTO("u1", RECENTLY));
        verify(erasure).erase(new UserDeletedEventDTO("u2", NOW.minus(RECENT_WINDOW)));
    }

    @Test
    void reconcile_shouldSkipATombstone_whenErasedAndDeletedBeforeTheRecentWindow() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u1", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1"))).thenReturn(Mono.just(Set.of("u1")));

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(1, 0, 0))
                .verifyComplete();

        verify(erasure, never()).erase(any());
    }

    @Test
    void reconcile_shouldTreatTheTombstoneAsRecent_whenDeletedAtIsMissing() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u1", null))));
        when(markers.erasedAmong(List.of("u1"))).thenReturn(Mono.just(Set.of("u1")));
        erasureSucceeds();

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(1, 1, 0))
                .verifyComplete();

        verify(erasure).erase(new UserDeletedEventDTO("u1", null));
    }

    @Test
    void reconcile_shouldFollowTheCursor_untilNextIsNull() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page("c1",
                new DeletedUser("u1", LONG_AGO), new DeletedUser("u2", LONG_AGO))));
        when(source.page("c1", PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u3", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1", "u2"))).thenReturn(Mono.just(Set.of("u1")));
        when(markers.erasedAmong(List.of("u3"))).thenReturn(Mono.just(Set.of()));
        erasureSucceeds();

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(3, 2, 0))
                .verifyComplete();

        verify(erasure).erase(new UserDeletedEventDTO("u2", LONG_AGO));
        verify(erasure).erase(new UserDeletedEventDTO("u3", LONG_AGO));
        verify(erasure, never()).erase(new UserDeletedEventDTO("u1", LONG_AGO));
    }

    @Test
    void reconcile_shouldEndThePass_whenAPageIsEmpty() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page("c1")));
        when(markers.erasedAmong(List.of())).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.reconcile())
                .expectNext(ReconciliationReport.NONE)
                .verifyComplete();

        verify(source, never()).page("c1", PAGE_SIZE);
        verifyNoInteractions(erasure);
    }

    @Test
    void reconcile_shouldEndThePass_whenTheCursorDoesNotMove() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page("c1", new DeletedUser("u1", LONG_AGO))));
        when(source.page("c1", PAGE_SIZE)).thenReturn(Mono.just(page("c1", new DeletedUser("u2", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1"))).thenReturn(Mono.just(Set.of("u1")));
        when(markers.erasedAmong(List.of("u2"))).thenReturn(Mono.just(Set.of("u2")));

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(2, 0, 0))
                .verifyComplete();

        verify(source, times(1)).page("c1", PAGE_SIZE);
        verifyNoInteractions(erasure);
    }

    @Test
    void reconcile_shouldCountTheFailureAndGoOn_whenOneErasureFails() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null,
                new DeletedUser("u1", LONG_AGO), new DeletedUser("u2", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1", "u2"))).thenReturn(Mono.just(Set.of()));
        when(erasure.erase(new UserDeletedEventDTO("u1", LONG_AGO)))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));
        when(erasure.erase(new UserDeletedEventDTO("u2", LONG_AGO))).thenReturn(Mono.empty());

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(2, 1, 1))
                .verifyComplete();
    }

    @Test
    void reconcile_shouldCountTheFailure_whenTheErasureThrows() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u1", LONG_AGO))));
        when(markers.erasedAmong(List.of("u1"))).thenReturn(Mono.just(Set.of()));
        when(erasure.erase(any())).thenThrow(new IllegalStateException("boom"));

        StepVerifier.create(service.reconcile())
                .expectNext(new ReconciliationReport(1, 0, 1))
                .verifyComplete();
    }

    @Test
    void reconcile_shouldFail_whenTheSourceCannotBeRead() {
        when(source.page(null, PAGE_SIZE))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE)));

        StepVerifier.create(service.reconcile())
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getErrorCode() == TechnicalErrorCode.USERS_UNAVAILABLE)
                .verify();

        verifyNoInteractions(markers, erasure);
    }

    @Test
    void reconcile_shouldReadTheNextPageOnlyAfterThisPagesErasures_whenTheNextPageCannotBeRead() {
        AtomicInteger completedErasures = new AtomicInteger();
        AtomicInteger completedWhenTheNextPageWasRead = new AtomicInteger(-1);
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page("c1",
                new DeletedUser("u1", LONG_AGO), new DeletedUser("u2", LONG_AGO))));
        when(source.page("c1", PAGE_SIZE)).thenReturn(Mono.defer(() -> {
            completedWhenTheNextPageWasRead.set(completedErasures.get());
            return Mono.error(new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE));
        }));
        when(markers.erasedAmong(List.of("u1", "u2"))).thenReturn(Mono.just(Set.of()));
        // Asynchronous like a real erasure: a failed read that did not wait for it would cancel it.
        when(erasure.erase(any())).thenAnswer(invocation -> Mono.delay(ASYNC_ERASURE)
                .doOnNext(tick -> completedErasures.incrementAndGet())
                .then());

        StepVerifier.create(service.reconcile())
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getErrorCode() == TechnicalErrorCode.USERS_UNAVAILABLE)
                .verify(Duration.ofSeconds(5));

        assertThat(completedWhenTheNextPageWasRead).hasValue(2);
        assertThat(completedErasures).hasValue(2);
    }

    @Test
    void reconcile_shouldFail_whenTheMarkersCannotBeRead() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null, new DeletedUser("u1", LONG_AGO))));
        when(markers.erasedAmong(anyCollection()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));

        StepVerifier.create(service.reconcile())
                .expectError(TechnicalException.class)
                .verify();

        verify(erasure, never()).erase(any());
    }

    @Test
    void reconcile_shouldReportZero_whenThereAreNoTombstones() {
        when(source.page(null, PAGE_SIZE)).thenReturn(Mono.just(page(null)));
        when(markers.erasedAmong(List.of())).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.reconcile())
                .expectNext(ReconciliationReport.NONE)
                .verifyComplete();
    }
}
