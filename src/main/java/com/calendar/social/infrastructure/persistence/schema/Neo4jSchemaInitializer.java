package com.calendar.social.infrastructure.persistence.schema;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/** Creates the uniqueness constraint on deletion markers at startup, retrying in the background. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.neo4j.schema-init", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class Neo4jSchemaInitializer implements SmartLifecycle {

    static final String DELETED_USER_CONSTRAINT =
            "CREATE CONSTRAINT deleted_user_id IF NOT EXISTS FOR (d:DeletedUser) REQUIRE d.userId IS UNIQUE";

    private final ReactiveNeo4jClient client;
    private final Duration firstBackoff;
    private final Duration maxBackoff;

    private volatile Disposable task;

    public Neo4jSchemaInitializer(
            ReactiveNeo4jClient client,
            @Value("${app.neo4j.schema-init.first-backoff:1s}") Duration firstBackoff,
            @Value("${app.neo4j.schema-init.max-backoff:5m}") Duration maxBackoff) {
        this.client = client;
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public void start() {
        // A self-driven startup task, like OutboxRelay in wely-users: nothing else subscribes to it.
        task = ensureConstraint()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, firstBackoff)
                        .maxBackoff(maxBackoff)
                        .doBeforeRetry(signal -> log.warn(
                                "Neo4j constraint deleted_user_id not ensured yet, retrying: {}",
                                signal.failure().getMessage())))
                .subscribe(
                        summary -> log.info("Neo4j constraint deleted_user_id ensured"),
                        error -> log.error("Neo4j constraint deleted_user_id could not be ensured", error));
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

    /** Creates the constraint; IF NOT EXISTS makes a second run a no-op. */
    Mono<ResultSummary> ensureConstraint() {
        return Mono.defer(() -> client.query(DELETED_USER_CONSTRAINT).run());
    }
}
