package com.calendar.social.infrastructure.persistence.schema;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Neo4jSchemaInitializerTest {

    private final ReactiveNeo4jClient client = mock(ReactiveNeo4jClient.class, RETURNS_DEEP_STUBS);
    private final ResultSummary summary = mock(ResultSummary.class);

    private Neo4jSchemaInitializer initializer(Duration firstBackoff) {
        return new Neo4jSchemaInitializer(client, firstBackoff, firstBackoff.multipliedBy(2));
    }

    @Test
    void ensureConstraint_shouldRunTheDeletedUserConstraintStatement() {
        when(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT).run()).thenReturn(Mono.just(summary));

        StepVerifier.create(initializer(Duration.ofMillis(1)).ensureConstraint()).expectNext(summary).verifyComplete();
    }

    @Test
    void start_shouldCreateTheDeletedUserConstraint() {
        when(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT).run()).thenReturn(Mono.just(summary));
        Neo4jSchemaInitializer initializer = initializer(Duration.ofMillis(1));

        initializer.start();
        try {
            verify(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT), timeout(2000)).run();
        } finally {
            initializer.stop();
        }
    }

    @Test
    void start_shouldRetry_whenNeo4jIsNotReachableYet() {
        AtomicInteger attempts = new AtomicInteger();
        when(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT).run()).thenReturn(Mono.defer(() ->
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
        when(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT).run()).thenReturn(Mono.defer(() -> {
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
        when(client.query(Neo4jSchemaInitializer.DELETED_USER_CONSTRAINT).run()).thenReturn(Mono.never());
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
