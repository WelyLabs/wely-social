package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@value #METRIC}: how many FRIENDSHIP_CHANGED records wait to be sent. A growing value means the
 * relay is stuck or Kafka is down.
 *
 * <p>A gauge is read on every scrape and must not block, so it reports a number refreshed in the
 * background by a count query, every {@code refresh-interval}. A failed refresh keeps the last value
 * and is counted as a technical failure of a job.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.friendship-outbox.backlog", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FriendshipOutboxBacklogGauge implements SmartLifecycle {

    public static final String METRIC = "wely.outbox.pending";
    public static final String OUTBOX_TAG = "outbox";
    /** This service's only outbox. */
    public static final String OUTBOX_NAME = "friendship";

    private final Neo4jFriendshipOutboxAdapter outbox;
    private final TechnicalErrorCounter errorCounter;
    private final Duration refreshInterval;
    private final AtomicLong pending = new AtomicLong();

    private volatile Disposable task;

    public FriendshipOutboxBacklogGauge(
            Neo4jFriendshipOutboxAdapter outbox,
            TechnicalErrorCounter errorCounter,
            MeterRegistry meterRegistry,
            @Value("${app.friendship-outbox.backlog.refresh-interval:30s}") Duration refreshInterval) {
        this.outbox = outbox;
        this.errorCounter = errorCounter;
        this.refreshInterval = refreshInterval;
        Gauge.builder(METRIC, pending, AtomicLong::get)
                .description("Outbox records not published yet")
                .tag(OUTBOX_TAG, OUTBOX_NAME)
                .register(meterRegistry);
    }

    @Override
    public void start() {
        // A self-driven background task, like the relay: nothing else subscribes to it.
        task = Flux.interval(Duration.ZERO, refreshInterval)
                // A refresh slower than the interval skips ticks instead of failing the loop.
                .onBackpressureDrop()
                .concatMap(tick -> refresh())
                .subscribe(
                        count -> { },
                        error -> log.error("Friendship outbox backlog gauge stopped refreshing", error));
    }

    @Override
    public void stop() {
        Disposable running = task;
        task = null;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return task != null;
    }

    /** The current value, as the gauge reports it. */
    long pending() {
        return pending.get();
    }

    /** One refresh: the new count, or empty after a failure, which leaves the last value in place. */
    Mono<Long> refresh() {
        return Mono.defer(outbox::countPending)
                .doOnNext(pending::set)
                .onErrorResume(error -> {
                    log.warn("Friendship outbox backlog not refreshed, keeping {}: {}", pending.get(), error.toString());
                    errorCounter.count(error, GlobalErrorHandler.SOURCE_JOB);
                    return Mono.empty();
                });
    }
}
