package com.calendar.social.infrastructure.reconciliation;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.domain.models.ReconciliationReport;
import com.calendar.social.domain.services.AccountReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Background loop re-applying the erasure from wely-users' tombstones: daily, or an hour after a failed pass. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.account-erasure.reconciliation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class DeletedUsersReconciliationLoop implements SmartLifecycle {

    private final AccountReconciliationService reconciliation;
    private final TechnicalErrorCounter errorCounter;
    private final Duration initialDelay;
    private final Duration interval;
    private final Duration retryInterval;

    private volatile Disposable loop;

    public DeletedUsersReconciliationLoop(
            AccountReconciliationService reconciliation,
            TechnicalErrorCounter errorCounter,
            @Value("${app.account-erasure.reconciliation.initial-delay:1m}") Duration initialDelay,
            @Value("${app.account-erasure.reconciliation.interval:24h}") Duration interval,
            @Value("${app.account-erasure.reconciliation.retry-interval:1h}") Duration retryInterval) {
        this.reconciliation = reconciliation;
        this.errorCounter = errorCounter;
        this.initialDelay = initialDelay;
        this.interval = interval;
        this.retryInterval = retryInterval;
    }

    @Override
    public void start() {
        // Owned by this lifecycle and disposed on stop; passOnce always emits a delay, so only stop ends the loop.
        loop = Mono.delay(initialDelay)
                .then(Mono.defer(this::passOnce))
                .expand(next -> Mono.delay(next).then(Mono.defer(this::passOnce)))
                .subscribe(
                        next -> { },
                        error -> {
                            log.error("Deleted-users reconciliation loop terminated and will not restart", error);
                            errorCounter.count(error, GlobalErrorHandler.SOURCE_RECONCILIATION);
                        },
                        () -> log.error("Deleted-users reconciliation loop completed and will not restart"));
        log.info("Deleted-users reconciliation started: first pass in {}, then every {}, {} after a failed pass",
                initialDelay, interval, retryInterval);
    }

    @Override
    public void stop() {
        Disposable running = loop;
        loop = null;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    /** One pass, cut after the interval; returns the delay before the next one. */
    Mono<Duration> passOnce() {
        return Mono.defer(reconciliation::reconcile)
                // No driver timeout bounds the erasures: a hung pass must delay the next one, not end the loop.
                .timeout(interval)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Reconciliation pass completed empty")))
                .map(this::nextDelay)
                .onErrorResume(error -> {
                    log.warn("Deleted-users reconciliation failed; next attempt in {}: {}", retryInterval,
                            error.toString());
                    errorCounter.count(error, GlobalErrorHandler.SOURCE_RECONCILIATION);
                    return Mono.just(retryInterval);
                });
    }

    private Duration nextDelay(ReconciliationReport report) {
        Duration next = report.failed() > 0 ? retryInterval : interval;
        log.info("Deleted-users reconciliation: {} tombstone(s), {} erased, {} failed; next pass in {}",
                report.tombstones(), report.erased(), report.failed(), next);
        return next;
    }
}
