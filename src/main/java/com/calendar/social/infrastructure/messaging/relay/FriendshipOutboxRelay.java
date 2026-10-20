package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.infrastructure.messaging.adapters.KafkaFriendshipOutboxDispatcher;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Background loop, started with the application, that sends pending FRIENDSHIP_CHANGED records.
 *
 * <p>Each pass sends the oldest pending records in version order and marks each one published once
 * Kafka has all its messages. A retriable failure (broker unreachable, timeout) ends the pass, so no
 * later record of the pair overtakes it, and the next passes back off up to {@code max-backoff}. A
 * record that can never go out — a permanent error, or {@code max-attempts} failures in a row — is
 * parked: kept for inspection, skipped from then on, and the pass goes on with the next one. A crash
 * between the send and the mark sends the record again: delivery is at-least-once, and consumers
 * drop a version they already hold.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.friendship-outbox.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FriendshipOutboxRelay implements SmartLifecycle {

    /** From this many failed attempts on, each further one is logged as an error. */
    static final long ALERT_AFTER_ATTEMPTS = 5;
    /** Caps the doubling of the backoff, far past any sensible max-backoff. */
    static final int MAX_BACKOFF_DOUBLINGS = 20;

    /** What became of one record in a pass. */
    enum Outcome { PUBLISHED, PARKED, RETRY_LATER }

    private final Neo4jFriendshipOutboxAdapter outbox;
    private final KafkaFriendshipOutboxDispatcher dispatcher;
    private final TechnicalErrorCounter errorCounter;
    private final Duration pollInterval;
    private final int batchSize;
    private final long maxAttempts;
    private final Duration maxBackoff;
    private final Duration purgeInterval;
    private final Duration retention;
    private final int purgeBatchSize;

    /** Passes ended by a failure since the last clean one: drives the backoff. */
    private final AtomicInteger failedPasses = new AtomicInteger();
    private volatile Disposable.Composite loops;

    public FriendshipOutboxRelay(
            Neo4jFriendshipOutboxAdapter outbox,
            KafkaFriendshipOutboxDispatcher dispatcher,
            TechnicalErrorCounter errorCounter,
            @Value("${app.friendship-outbox.relay.poll-interval:2s}") Duration pollInterval,
            @Value("${app.friendship-outbox.relay.batch-size:100}") int batchSize,
            @Value("${app.friendship-outbox.relay.max-attempts:20}") long maxAttempts,
            @Value("${app.friendship-outbox.relay.max-backoff:5m}") Duration maxBackoff,
            @Value("${app.friendship-outbox.purge.interval:1h}") Duration purgeInterval,
            @Value("${app.friendship-outbox.purge.retention:7d}") Duration retention,
            @Value("${app.friendship-outbox.purge.batch-size:10000}") int purgeBatchSize) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.errorCounter = errorCounter;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.maxBackoff = maxBackoff;
        this.purgeInterval = purgeInterval;
        this.retention = retention;
        this.purgeBatchSize = purgeBatchSize;
    }

    @Override
    public void start() {
        Disposable.Composite started = Disposables.composite();
        started.add(loop("drain", this::nextDrainDelay, this::drainOnce));
        started.add(loop("purge", () -> purgeInterval, this::purgeOnce));
        loops = started;
        log.info("Friendship outbox relay started: draining every {} in batches of {}, parking a record after "
                        + "{} failed attempts, purging published and parked records older than {} every {}",
                pollInterval, batchSize, maxAttempts, retention, purgeInterval);
    }

    @Override
    public void stop() {
        Disposable.Composite running = loops;
        loops = null;
        if (running != null) {
            running.dispose();
            log.info("Friendship outbox relay stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return loops != null;
    }

    /**
     * Repeats a pass forever, waiting {@code delay} after each one, so passes never overlap.
     * Errors are caught inside the repeated Mono: one reaching {@code repeatWhen} would stop the loop.
     */
    private Disposable loop(String name, Supplier<Duration> delay, Supplier<Mono<Long>> pass) {
        // A self-driven background task, like OutboxRelay in wely-users: nothing else subscribes to it.
        return Mono.defer(pass)
                .onErrorResume(error -> {
                    log.error("Friendship outbox {} pass failed; next attempt in {}", name, delay.get(), error);
                    errorCounter.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.concatMap(done -> Mono.delay(delay.get())))
                .subscribe(
                        count -> { },
                        error -> {
                            log.error("Friendship outbox {} loop terminated and will not restart", name, error);
                            errorCounter.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                        });
    }

    /** The poll interval after a clean pass; doubled for each failed pass in a row, up to max-backoff. */
    Duration nextDrainDelay() {
        int doublings = Math.min(failedPasses.get(), MAX_BACKOFF_DOUBLINGS);
        Duration backedOff = pollInterval.multipliedBy(1L << doublings);
        return backedOff.compareTo(maxBackoff) > 0 ? maxBackoff : backedOff;
    }

    /**
     * One pass, in version order, past parked records, stopping at the first retriable failure.
     * Returns how many records went out.
     */
    Mono<Long> drainOnce() {
        // Collected first: the read's cursor is closed before the marks write to the same records.
        return outbox.findPending(batchSize).collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::publish)
                .takeUntil(outcome -> outcome == Outcome.RETRY_LATER)
                .collectList()
                .map(this::settlePass)
                .doOnError(error -> failedPasses.incrementAndGet());
    }

    private long settlePass(List<Outcome> outcomes) {
        if (outcomes.contains(Outcome.RETRY_LATER)) {
            failedPasses.incrementAndGet();
        } else {
            failedPasses.set(0);
        }
        long published = outcomes.stream().filter(outcome -> outcome == Outcome.PUBLISHED).count();
        if (published > 0) {
            log.info("Friendship outbox relay published {} record(s)", published);
        }
        return published;
    }

    private Mono<Outcome> publish(FriendshipOutboxEventEntity event) {
        return dispatcher.dispatch(event)
                .then(Mono.defer(() -> outbox.markPublished(event.getVersion())))
                .thenReturn(Outcome.PUBLISHED)
                .onErrorResume(error -> handleFailure(event, error));
    }

    /** The payload is not logged: it names users. */
    private Mono<Outcome> handleFailure(FriendshipOutboxEventEntity event, Throwable error) {
        long attempt = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        errorCounter.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
        boolean retriable = PublicationErrorClassifier.isRetriable(error);
        if (!retriable || attempt >= maxAttempts) {
            return park(event, attempt, retriable, error);
        }
        if (attempt >= ALERT_AFTER_ATTEMPTS) {
            log.error("FRIENDSHIP_CHANGED record {} ({}) failed on attempt {}; later records wait behind it: {}",
                    event.getVersion(), event.getType(), attempt, describe(error));
        } else {
            log.warn("FRIENDSHIP_CHANGED record {} ({}) failed on attempt {}, retried next pass: {}",
                    event.getVersion(), event.getType(), attempt, describe(error));
        }
        return outbox.recordFailure(event.getVersion(), describe(error))
                .onErrorResume(recordError -> {
                    errorCounter.count(recordError, GlobalErrorHandler.SOURCE_OUTBOX);
                    return Mono.just(0L);
                })
                .thenReturn(Outcome.RETRY_LATER);
    }

    /** Gives up on the record so the ones after it go out. Unparked, it stays pending and ends the pass. */
    private Mono<Outcome> park(FriendshipOutboxEventEntity event, long attempt, boolean retriable, Throwable error) {
        log.error("FRIENDSHIP_CHANGED record {} ({}) parked on attempt {} ({}); it will not be sent, "
                        + "later records go on without it: {}", event.getVersion(), event.getType(), attempt,
                retriable ? "too many failures" : "permanent error", describe(error));
        return outbox.park(event.getVersion(), describe(error))
                .thenReturn(Outcome.PARKED)
                .onErrorResume(parkError -> {
                    errorCounter.count(parkError, GlobalErrorHandler.SOURCE_OUTBOX);
                    log.error("FRIENDSHIP_CHANGED record {} could not be parked; retried next pass: {}",
                            event.getVersion(), parkError.toString());
                    return Mono.just(Outcome.RETRY_LATER);
                });
    }

    /** The error and, when the binder wrapped it, its root cause: what actually went wrong. */
    static String describe(Throwable error) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
        return root == error ? error.toString() : error + " caused by " + root;
    }

    /** Deletes published and parked records older than the retention window, a bounded batch per pass. */
    Mono<Long> purgeOnce() {
        return outbox.purgeSettledBefore(Instant.now().minus(retention), purgeBatchSize)
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.info("Friendship outbox purge removed {} published or parked record(s) older than {}",
                                removed, retention);
                    }
                });
    }
}
