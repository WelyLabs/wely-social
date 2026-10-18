package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.infrastructure.messaging.adapters.KafkaFriendshipOutboxDispatcher;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendshipOutboxRelayTest {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final Neo4jFriendshipOutboxAdapter outbox = mock(Neo4jFriendshipOutboxAdapter.class);
    private final KafkaFriendshipOutboxDispatcher dispatcher = mock(KafkaFriendshipOutboxDispatcher.class);
    private final FriendshipOutboxRelay relay = new FriendshipOutboxRelay(outbox, dispatcher, Duration.ofMillis(10),
            100, Duration.ofMillis(10), RETENTION, 500);

    private static FriendshipOutboxEventEntity record(long version, long attempts) {
        FriendshipOutboxEventEntity record = new FriendshipOutboxEventEntity();
        record.setVersion(version);
        record.setType("ACCEPTED");
        record.setPairKey("alice:bob");
        record.setAttempts(attempts);
        return record;
    }

    @BeforeEach
    void setUp() {
        lenient().when(outbox.markPublished(anyLong())).thenReturn(Mono.just(1L));
        lenient().when(outbox.recordFailure(anyLong(), anyString())).thenReturn(Mono.just(1L));
        lenient().when(outbox.purgePublishedBefore(any(), anyInt())).thenReturn(Mono.just(0L));
    }

    @Test
    void drainOnce_shouldSendAndMarkEachRecord_inVersionOrder() {
        FriendshipOutboxEventEntity first = record(1, 0);
        FriendshipOutboxEventEntity second = record(2, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(first, second));
        when(dispatcher.dispatch(any())).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(2L).verifyComplete();

        InOrder order = inOrder(dispatcher, outbox);
        order.verify(dispatcher).dispatch(first);
        order.verify(outbox).markPublished(1L);
        order.verify(dispatcher).dispatch(second);
        order.verify(outbox).markPublished(2L);
    }

    @Test
    @DisplayName("the first failure ends the pass, so no later record of the pair can overtake it")
    void drainOnce_shouldStopAtTheFirstFailure_andRecordIt() {
        FriendshipOutboxEventEntity first = record(1, 0);
        FriendshipOutboxEventEntity second = record(2, 0);
        FriendshipOutboxEventEntity third = record(3, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(first, second, third));
        when(dispatcher.dispatch(first)).thenReturn(Mono.empty());
        when(dispatcher.dispatch(second)).thenReturn(Mono.error(new IllegalStateException("broker down")));

        StepVerifier.create(relay.drainOnce()).expectNext(1L).verifyComplete();

        verify(outbox).markPublished(1L);
        verify(outbox, never()).markPublished(2L);
        verify(outbox).recordFailure(eq(2L), argThat(error -> error.contains("broker down")));
        verify(dispatcher, never()).dispatch(third);
    }

    @Test
    @DisplayName("a record past the alert threshold is still retried: it is never skipped")
    void drainOnce_shouldKeepRetrying_aRecordThatFailedManyTimes() {
        FriendshipOutboxEventEntity stuck = record(1, FriendshipOutboxRelay.ALERT_AFTER_ATTEMPTS + 10);
        when(outbox.findPending(100)).thenReturn(Flux.just(stuck));
        when(dispatcher.dispatch(stuck)).thenReturn(Mono.error(new IllegalStateException("broker down")));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(outbox).recordFailure(eq(1L), anyString());
    }

    @Test
    @DisplayName("a mark that fails after the send counts as a failure: the record goes out again next pass")
    void drainOnce_shouldRecordAFailure_whenTheMarkFails() {
        FriendshipOutboxEventEntity first = record(1, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(first));
        when(dispatcher.dispatch(first)).thenReturn(Mono.empty());
        when(outbox.markPublished(1L)).thenReturn(Mono.error(new IllegalStateException("graph down")));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(outbox).recordFailure(eq(1L), anyString());
    }

    @Test
    void drainOnce_shouldStillEndThePass_whenTheFailureCannotBeRecorded() {
        FriendshipOutboxEventEntity first = record(1, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(first));
        when(dispatcher.dispatch(first)).thenReturn(Mono.error(new IllegalStateException("broker down")));
        when(outbox.recordFailure(anyLong(), anyString())).thenReturn(Mono.error(new IllegalStateException("graph down")));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();
    }

    @Test
    void drainOnce_shouldPublishNothing_whenNothingIsPending() {
        when(outbox.findPending(100)).thenReturn(Flux.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void drainOnce_shouldFail_whenThePendingRecordsCannotBeRead() {
        when(outbox.findPending(100)).thenReturn(Flux.error(new IllegalStateException("graph down")));

        StepVerifier.create(relay.drainOnce()).verifyError(IllegalStateException.class);
    }

    @Test
    void purgeOnce_shouldDeleteRecordsPublishedBeforeTheRetentionWindow() {
        when(outbox.purgePublishedBefore(any(), eq(500))).thenReturn(Mono.just(3L));
        Instant earliest = Instant.now().minus(RETENTION);

        StepVerifier.create(relay.purgeOnce()).expectNext(3L).verifyComplete();

        Instant latest = Instant.now().minus(RETENTION);
        verify(outbox).purgePublishedBefore(argThat(cutoff -> !cutoff.isBefore(earliest) && !cutoff.isAfter(latest)),
                eq(500));
    }

    @Test
    void start_shouldDrainAndPurgeRepeatedly_andSurviveAFailedPass() {
        when(outbox.findPending(100))
                .thenReturn(Flux.error(new IllegalStateException("graph down")))
                .thenReturn(Flux.empty());

        relay.start();
        try {
            verify(outbox, timeout(2000).atLeast(3)).findPending(100);
            verify(outbox, timeout(2000).atLeast(2)).purgePublishedBefore(any(), eq(500));
        } finally {
            relay.stop();
        }
    }

    @Test
    void isRunning_shouldReflectStartAndStop() {
        when(outbox.findPending(100)).thenReturn(Flux.never());

        assertThat(relay.isRunning()).isFalse();
        relay.start();
        assertThat(relay.isRunning()).isTrue();
        relay.stop();
        assertThat(relay.isRunning()).isFalse();
        relay.stop();
        assertThat(relay.isRunning()).isFalse();
    }
}
