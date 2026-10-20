package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import com.calendar.social.infrastructure.persistence.repositories.FriendshipOutboxEventRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Neo4jFriendshipOutboxAdapterTest {

    private final FriendshipOutboxEventRepository repository = mock(FriendshipOutboxEventRepository.class);
    private final Neo4jFriendshipOutboxAdapter adapter = new Neo4jFriendshipOutboxAdapter(repository,
            "friendship-snapshot-v2");

    @Test
    void append_shouldWriteTheTypeByName_andBothUsers() {
        when(repository.append("ACCEPTED", "bob", "alice", null)).thenReturn(Mono.just(new FriendshipOutboxEventEntity()));

        StepVerifier.create(adapter.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.ACCEPTED, "bob", "alice")))
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(repository).append("ACCEPTED", "bob", "alice", null);
    }

    @Test
    void append_shouldWriteThePreviousClosenessByName() {
        when(repository.append("CLOSENESS_CHANGED", "bob", "alice", "CLOSE"))
                .thenReturn(Mono.just(new FriendshipOutboxEventEntity()));

        StepVerifier.create(adapter.append(new FriendshipEvent(FriendshipEventType.CLOSENESS_CHANGED, "bob", "alice",
                        Closeness.CLOSE)))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void append_shouldFail_whenNoRecordCameBack() {
        when(repository.append(anyString(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.append(new FriendshipEvent(FriendshipEventType.REMOVED, "bob", "alice",
                        Closeness.FRIEND)))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorCode())
                        .isEqualTo(TechnicalErrorCode.DATABASE_ERROR)).verify(Duration.ofSeconds(10));
    }

    @Test
    void enqueueSnapshotOnce_shouldUseTheConfiguredMarker_andTheSnapshotType() {
        when(repository.enqueueSnapshotOnce("friendship-snapshot-v2", "SNAPSHOT")).thenReturn(Mono.just(4L));

        StepVerifier.create(adapter.enqueueSnapshotOnce()).expectNext(4L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void enqueueSnapshotOnce_shouldBeEmpty_whenItAlreadyRan() {
        when(repository.enqueueSnapshotOnce("friendship-snapshot-v2", "SNAPSHOT")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.enqueueSnapshotOnce()).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findPending_shouldReturnTheRepositorysRecords() {
        FriendshipOutboxEventEntity record = new FriendshipOutboxEventEntity();
        when(repository.findPending(100)).thenReturn(Flux.just(record));

        StepVerifier.create(adapter.findPending(100)).expectNext(record).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldMarkTheVersion() {
        when(repository.markPublished(42L)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.markPublished(42L)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordFailure_shouldStoreAShortErrorAsIs() {
        when(repository.recordFailure(42L, "broker down")).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.recordFailure(42L, "broker down")).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordFailure_shouldTruncateALongError() {
        String longError = "x".repeat(Neo4jFriendshipOutboxAdapter.MAX_STORED_ERROR_LENGTH + 50);
        String truncated = "x".repeat(Neo4jFriendshipOutboxAdapter.MAX_STORED_ERROR_LENGTH);
        when(repository.recordFailure(42L, truncated)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.recordFailure(42L, longError)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordFailure_shouldAcceptANullError() {
        when(repository.recordFailure(anyLong(), org.mockito.ArgumentMatchers.isNull())).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.recordFailure(42L, null)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void park_shouldStoreAShortErrorAsIs() {
        when(repository.park(42L, "record too large")).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.park(42L, "record too large")).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void park_shouldTruncateALongError() {
        String longError = "x".repeat(Neo4jFriendshipOutboxAdapter.MAX_STORED_ERROR_LENGTH + 50);
        String truncated = "x".repeat(Neo4jFriendshipOutboxAdapter.MAX_STORED_ERROR_LENGTH);
        when(repository.park(42L, truncated)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.park(42L, longError)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void park_shouldFail_whenTheGraphFails() {
        when(repository.park(42L, "too large")).thenReturn(Mono.error(new IllegalStateException("graph down")));

        StepVerifier.create(adapter.park(42L, "too large")).expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void purgeSettledBefore_shouldPassTheCutoffInUtc_andTheBatchSize() {
        Instant cutoff = Instant.parse("2026-10-11T00:00:00Z");
        when(repository.deleteSettledBefore(cutoff.atZone(ZoneOffset.UTC), 500)).thenReturn(Mono.just(7L));

        StepVerifier.create(adapter.purgeSettledBefore(cutoff, 500)).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));
    }
}
