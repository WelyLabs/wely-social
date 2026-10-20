package com.calendar.social.infrastructure.persistence.schema;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Neo4jSchemaInitializerTest {

    private final ReactiveNeo4jClient client = mock(ReactiveNeo4jClient.class);
    private final ResultSummary summary = mock(ResultSummary.class);

    private Neo4jSchemaInitializer initializer(Duration firstBackoff) {
        return new Neo4jSchemaInitializer(client, firstBackoff, firstBackoff.multipliedBy(2));
    }

    private ReactiveNeo4jClient.UnboundRunnableSpec stub(String statement, Mono<ResultSummary> result) {
        ReactiveNeo4jClient.UnboundRunnableSpec spec = mock(ReactiveNeo4jClient.UnboundRunnableSpec.class);
        when(spec.run()).thenReturn(result);
        when(client.query(statement)).thenReturn(spec);
        return spec;
    }

    private void stubAll(Mono<ResultSummary> result) {
        Neo4jSchemaInitializer.STATEMENTS.forEach(statement -> stub(statement, result));
    }

    @Test
    void ensureSchema_shouldRunEveryStatementInOrder() {
        stubAll(Mono.just(summary));

        StepVerifier.create(initializer(Duration.ofMillis(1)).ensureSchema())
                .expectNext((long) Neo4jSchemaInitializer.STATEMENTS.size())
                .expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(client);
        Neo4jSchemaInitializer.STATEMENTS.forEach(statement -> order.verify(client).query(statement));
    }

    @Test
    void ensureSchema_shouldCoverTheDeletionMarkersAndTheFriendshipOutbox() {
        assertThat(Neo4jSchemaInitializer.STATEMENTS).containsExactly(
                Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT,
                Neo4jSchemaInitializer.FRIENDSHIP_CLOCK_CONSTRAINT,
                Neo4jSchemaInitializer.FRIENDSHIP_OUTBOX_VERSION_CONSTRAINT,
                Neo4jSchemaInitializer.FRIENDSHIP_OUTBOX_PUBLISHED_INDEX,
                Neo4jSchemaInitializer.FRIENDSHIP_OUTBOX_PARKED_INDEX,
                Neo4jSchemaInitializer.MIGRATION_CONSTRAINT);
        assertThat(Neo4jSchemaInitializer.STATEMENTS).allMatch(statement -> statement.contains("IF NOT EXISTS"));
    }

    @Test
    void ensureSchema_shouldStopAtTheFirstFailingStatement() {
        stubAll(Mono.just(summary));
        stub(Neo4jSchemaInitializer.FRIENDSHIP_CLOCK_CONSTRAINT, Mono.error(new IllegalStateException("Neo4j down")));

        StepVerifier.create(initializer(Duration.ofMillis(1)).ensureSchema())
                .expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));

        verify(client, never()).query(Neo4jSchemaInitializer.MIGRATION_CONSTRAINT);
    }

    @Test
    void start_shouldCreateTheSchema() {
        stubAll(Mono.just(summary));
        Neo4jSchemaInitializer initializer = initializer(Duration.ofMillis(1));

        initializer.start();
        try {
            verify(client, timeout(2000)).query(Neo4jSchemaInitializer.MIGRATION_CONSTRAINT);
        } finally {
            initializer.stop();
        }
    }

    @Test
    void start_shouldRetry_whenNeo4jIsNotReachableYet() {
        AtomicInteger attempts = new AtomicInteger();
        stubAll(Mono.just(summary));
        stub(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT, Mono.defer(() ->
                attempts.incrementAndGet() < 3 ? Mono.error(new IllegalStateException("Neo4j starting")) : Mono.just(summary)));
        Neo4jSchemaInitializer initializer = initializer(Duration.ofMillis(1));

        initializer.start();
        try {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(attempts).hasValue(3));
        } finally {
            initializer.stop();
        }
    }

    @Test
    void stop_shouldCancelAPendingRetry() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        stub(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT, Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("Neo4j down"));
        }));
        Neo4jSchemaInitializer initializer = initializer(Duration.ofMillis(200));

        // The first attempt runs on the caller's thread; the retry waits at least 100 ms.
        initializer.start();
        assertThat(attempts).hasValue(1);
        initializer.stop();
        Thread.sleep(600);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void isRunning_shouldReflectStartAndStop() {
        stub(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT, Mono.never());
        Neo4jSchemaInitializer initializer = initializer(Duration.ofMillis(1));

        assertThat(initializer.isRunning()).isFalse();
        initializer.start();
        assertThat(initializer.isRunning()).isTrue();
        initializer.stop();
        assertThat(initializer.isRunning()).isFalse();
        initializer.stop();
        assertThat(initializer.isRunning()).isFalse();
    }
}
