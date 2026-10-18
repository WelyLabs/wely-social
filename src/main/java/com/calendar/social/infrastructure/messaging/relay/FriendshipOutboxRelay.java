package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.infrastructure.messaging.adapters.KafkaFriendshipOutboxDispatcher;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Background loop, started with the application, that sends pending FRIENDSHIP_CHANGED records.
 *
 * <p>Each pass sends the oldest pending records in version order and marks each one published once
 * Kafka has it. The first failure ends the pass and is retried on the next one, without limit:
 * skipping a record would let a later one of the same pair overtake it. A crash between the send
 * and the mark sends the record again: delivery is at-least-once, and consumers drop a version they
 * already hold.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.friendship-outbox.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FriendshipOutboxRelay implements SmartLifecycle {

    /** From this many failed attempts on, each further one is logged as an error. */
    static final long ALERT_AFTER_ATTEMPTS = 5;

    private final Neo4jFriendshipOutboxAdapter outbox;
    private final KafkaFriendshipOutboxDispatcher dispatcher;
    private final Duration pollInterval;
    private final int batchSize;
    private final Duration purgeInterval;
    private final Duration retention;
    private final int purgeBatchSize;

    private volatile Disposable.Composite loops;

    public FriendshipOutboxRelay(
            Neo4jFriendshipOutboxAdapter outbox,
            KafkaFriendshipOutboxDispatcher dispatcher,
            @Value("${app.friendship-outbox.relay.poll-interval:2s}") Duration pollInterval,
            @Value("${app.friendship-outbox.relay.batch-size:100}") int batchSize,
            @Value("${app.friendship-outbox.purge.interval:1h}") Duration purgeInterval,
            @Value("${app.friendship-outbox.purge.retention:7d}") Duration retention,
            @Value("${app.friendship-outbox.purge.batch-size:10000}") int purgeBatchSize) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.purgeInterval = purgeInterval;
        this.retention = retention;
        this.purgeBatchSize = purgeBatchSize;
    }

    @Override
    public void start() {
        Disposable.Composite started = Disposables.composite();
        started.add(loop("drain", pollInterval, this::drainOnce));
        started.add(loop("purge", purgeInterval, this::purgeOnce));
        loops = started;
        log.info("Friendship outbox relay started: draining every {} in batches of {}, purging published "
                + "records older than {} every {}", pollInterval, batchSize, retention, purgeInterval);
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
     * Repeats a pass forever, waiting {@code period} after each one, so passes never overlap.
     * Errors are caught inside the repeated Mono: one reaching {@code repeatWhen} would stop the loop.
     */
    private Disposable loop(String name, Duration period, Supplier<Mono<Long>> pass) {
        // A self-driven background task, like OutboxRelay in wely-users: nothing else subscribes to it.
        return Mono.defer(pass)
                .onErrorResume(error -> {
                    log.error("Friendship outbox {} pass failed; next attempt in {}", name, period, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(period))
                .subscribe(
                        count -> { },
                        error -> log.error("Friendship outbox {} loop terminated and will not restart", name, error));
    }

    /** One pass, in version order, stopping at the first failure. Returns how many records went out. */
    Mono<Long> drainOnce() {
        // Collected first: the read's cursor is closed before the marks write to the same records.
        return outbox.findPending(batchSize).collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::publish)
                .takeWhile(Boolean::booleanValue)
                .count()
                .doOnNext(published -> {
                    if (published > 0) {
                        log.info("Friendship outbox relay published {} record(s)", published);
                    }
                });
    }

    /** True once sent and marked; false, with the failure recorded, otherwise. */
    private Mono<Boolean> publish(FriendshipOutboxEventEntity event) {
        return dispatcher.dispatch(event)
                .then(Mono.defer(() -> outbox.markPublished(event.getVersion())))
                .thenReturn(true)
                .onErrorResume(error -> recordFailure(event, error).thenReturn(false));
    }

    /** The payload is not logged: it names users. */
    private Mono<Long> recordFailure(FriendshipOutboxEventEntity event, Throwable error) {
        long attempt = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        if (attempt >= ALERT_AFTER_ATTEMPTS) {
            log.error("FRIENDSHIP_CHANGED record {} ({}) failed on attempt {}; later records wait behind it: {}",
                    event.getVersion(), event.getType(), attempt, error.toString());
        } else {
            log.warn("FRIENDSHIP_CHANGED record {} ({}) failed on attempt {}, retried next pass: {}",
                    event.getVersion(), event.getType(), attempt, error.toString());
        }
        return outbox.recordFailure(event.getVersion(), error.toString())
                .onErrorResume(recordError -> Mono.just(0L));
    }

    /** Deletes published records older than the retention window, a bounded batch per pass. */
    Mono<Long> purgeOnce() {
        return outbox.purgePublishedBefore(Instant.now().minus(retention), purgeBatchSize)
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.info("Friendship outbox purge removed {} published record(s) older than {}",
                                removed, retention);
                    }
                });
    }
}
