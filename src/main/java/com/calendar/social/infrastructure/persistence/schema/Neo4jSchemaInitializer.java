package com.calendar.social.infrastructure.persistence.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/** Creates the graph's constraints and indexes at startup, retrying in the background. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.neo4j.schema-init", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class Neo4jSchemaInitializer implements SmartLifecycle {

    static final String DELETED_USER_CONSTRAINT =
            "CREATE CONSTRAINT deleted_user_id IF NOT EXISTS FOR (d:DeletedUser) REQUIRE d.userId IS UNIQUE";
    /** One clock: a concurrent first MERGE could otherwise create two, and hand out a version twice. */
    static final String FRIENDSHIP_CLOCK_CONSTRAINT =
            "CREATE CONSTRAINT friendship_clock_name IF NOT EXISTS FOR (c:FriendshipClock) REQUIRE c.name IS UNIQUE";
    static final String FRIENDSHIP_OUTBOX_VERSION_CONSTRAINT = "CREATE CONSTRAINT friendship_outbox_version "
            + "IF NOT EXISTS FOR (e:FriendshipOutboxEvent) REQUIRE e.version IS UNIQUE";
    /** For the purge, which looks records up by publication date. */
    static final String FRIENDSHIP_OUTBOX_PUBLISHED_INDEX = "CREATE INDEX friendship_outbox_published_at "
            + "IF NOT EXISTS FOR (e:FriendshipOutboxEvent) ON (e.publishedAt)";
    static final String MIGRATION_CONSTRAINT =
            "CREATE CONSTRAINT migration_name IF NOT EXISTS FOR (m:Migration) REQUIRE m.name IS UNIQUE";

    static final List<String> STATEMENTS = List.of(DELETED_USER_CONSTRAINT, FRIENDSHIP_CLOCK_CONSTRAINT,
            FRIENDSHIP_OUTBOX_VERSION_CONSTRAINT, FRIENDSHIP_OUTBOX_PUBLISHED_INDEX, MIGRATION_CONSTRAINT);

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
        task = ensureSchema()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, firstBackoff)
                        .maxBackoff(maxBackoff)
                        .doBeforeRetry(signal -> log.warn(
                                "Neo4j constraints not ensured yet, retrying: {}",
                                signal.failure().getMessage())))
                .subscribe(
                        count -> log.info("Neo4j constraints and indexes ensured ({})", count),
                        error -> log.error("Neo4j constraints could not be ensured", error));
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

    /** Runs every statement in order; IF NOT EXISTS makes a second run a no-op. Returns how many ran. */
    Mono<Long> ensureSchema() {
        return Flux.fromIterable(STATEMENTS)
                .concatMap(statement -> Mono.defer(() -> client.query(statement).run()))
                .count();
    }
}
