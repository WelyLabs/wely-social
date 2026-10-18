package com.calendar.social.infrastructure.persistence.schema;

import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendshipSnapshotMigrationTest {

    private final Neo4jFriendshipOutboxAdapter outbox = mock(Neo4jFriendshipOutboxAdapter.class);

    private FriendshipSnapshotMigration migration(Duration firstBackoff) {
        return new FriendshipSnapshotMigration(outbox, firstBackoff, firstBackoff.multipliedBy(2));
    }

    @Test
    void start_shouldEnqueueTheSnapshot() {
        when(outbox.enqueueSnapshotOnce()).thenReturn(Mono.just(3L));
        FriendshipSnapshotMigration migration = migration(Duration.ofMillis(1));

        migration.start();
        try {
            verify(outbox, timeout(2000)).enqueueSnapshotOnce();
        } finally {
            migration.stop();
        }
    }

    @Test
    void start_shouldSucceed_whenTheSnapshotAlreadyRan() {
        when(outbox.enqueueSnapshotOnce()).thenReturn(Mono.empty());
        FriendshipSnapshotMigration migration = migration(Duration.ofMillis(1));

        migration.start();
        try {
            verify(outbox, timeout(2000)).enqueueSnapshotOnce();
        } finally {
            migration.stop();
        }
    }

    @Test
    void start_shouldRetry_whenNeo4jIsNotReachableYet() {
        AtomicInteger attempts = new AtomicInteger();
        when(outbox.enqueueSnapshotOnce()).thenReturn(Mono.defer(() ->
                attempts.incrementAndGet() < 3 ? Mono.error(new IllegalStateException("Neo4j starting")) : Mono.just(0L)));
        FriendshipSnapshotMigration migration = migration(Duration.ofMillis(1));

        migration.start();
        try {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(attempts).hasValue(3));
        } finally {
            migration.stop();
        }
    }

    @Test
    void stop_shouldCancelAPendingRetry() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        when(outbox.enqueueSnapshotOnce()).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("Neo4j down"));
        }));
        FriendshipSnapshotMigration migration = migration(Duration.ofMillis(200));

        migration.start();
        assertThat(attempts).hasValue(1);
        migration.stop();
        Thread.sleep(600);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void isRunning_shouldReflectStartAndStop() {
        when(outbox.enqueueSnapshotOnce()).thenReturn(Mono.never());
        FriendshipSnapshotMigration migration = migration(Duration.ofMillis(1));

        assertThat(migration.isRunning()).isFalse();
        migration.start();
        assertThat(migration.isRunning()).isTrue();
        migration.stop();
        assertThat(migration.isRunning()).isFalse();
        migration.stop();
        assertThat(migration.isRunning()).isFalse();
    }
}
