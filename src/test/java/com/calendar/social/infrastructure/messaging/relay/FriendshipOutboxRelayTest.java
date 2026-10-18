package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.infrastructure.messaging.adapters.KafkaFriendshipOutboxDispatcher;
import com.calendar.social.infrastructure.messaging.adapters.UnpublishableRecordException;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.apache.kafka.common.errors.RecordTooLargeException;
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
    private static final long MAX_ATTEMPTS = 20;
    private static final Duration POLL = Duration.ofMillis(10);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(50);

    private final Neo4jFriendshipOutboxAdapter outbox = mock(Neo4jFriendshipOutboxAdapter.class);
    private final KafkaFriendshipOutboxDispatcher dispatcher = mock(KafkaFriendshipOutboxDispatcher.class);
    private final FriendshipOutboxRelay relay = new FriendshipOutboxRelay(outbox, dispatcher, POLL, 100,
            MAX_ATTEMPTS, MAX_BACKOFF, Duration.ofMillis(10), RETENTION, 500);

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
        lenient().when(outbox.park(anyLong(), anyString())).thenReturn(Mono.just(1L));
        lenient().when(outbox.purgeSettledBefore(any(), anyInt())).thenReturn(Mono.just(0L));
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
        verify(outbox, never()).park(anyLong(), anyString());
    }

    @Test
    @DisplayName("a permanent error parks the record, and the pass goes on with the next one")
    void drainOnce_shouldParkARecordThatCanNeverBeSent_andContinue() {
        FriendshipOutboxEventEntity tooLarge = record(1, 0);
        FriendshipOutboxEventEntity next = record(2, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(tooLarge, next));
        when(dispatcher.dispatch(tooLarge)).thenReturn(Mono.error(
                new RuntimeException("send failed", new RecordTooLargeException("too large"))));
        when(dispatcher.dispatch(next)).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(1L).verifyComplete();

        verify(outbox).park(eq(1L), argThat(error -> error.contains("send failed") && error.contains("too large")));
        verify(outbox, never()).recordFailure(anyLong(), anyString());
        verify(outbox, never()).markPublished(1L);
        verify(outbox).markPublished(2L);
        assertThat(relay.nextDrainDelay()).isEqualTo(POLL);
    }

    @Test
    void drainOnce_shouldParkARecordWhoseMessagesCannotBeBuilt() {
        FriendshipOutboxEventEntity broken = record(1, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(broken));
        when(dispatcher.dispatch(broken)).thenReturn(Mono.error(
                new UnpublishableRecordException(1L, new IllegalArgumentException("unmappable"))));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(outbox).park(eq(1L), anyString());
    }

    @Test
    @DisplayName("a retriable error parks the record too once it reaches the attempt limit")
    void drainOnce_shouldPark_onTheLastAllowedAttempt_whateverTheError() {
        FriendshipOutboxEventEntity stuck = record(1, MAX_ATTEMPTS - 1);
        FriendshipOutboxEventEntity next = record(2, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(stuck, next));
        when(dispatcher.dispatch(stuck)).thenReturn(Mono.error(new IllegalStateException("broker down")));
        when(dispatcher.dispatch(next)).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(1L).verifyComplete();

        verify(outbox).park(eq(1L), argThat(error -> error.contains("broker down")));
        verify(outbox).markPublished(2L);
    }

    @Test
    @DisplayName("a record that cannot be parked stays pending and ends the pass, so nothing overtakes it")
    void drainOnce_shouldEndThePass_whenTheParkFails() {
        FriendshipOutboxEventEntity tooLarge = record(1, 0);
        FriendshipOutboxEventEntity next = record(2, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(tooLarge, next));
        when(dispatcher.dispatch(tooLarge)).thenReturn(Mono.error(new RecordTooLargeException("too large")));
        when(outbox.park(anyLong(), anyString())).thenReturn(Mono.error(new IllegalStateException("graph down")));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(dispatcher, never()).dispatch(next);
    }

    @Test
    @DisplayName("a record past the alert threshold, below the attempt limit, is still retried")
    void drainOnce_shouldKeepRetrying_aRecordThatFailedManyTimes() {
        FriendshipOutboxEventEntity stuck = record(1, FriendshipOutboxRelay.ALERT_AFTER_ATTEMPTS + 10);
        when(outbox.findPending(100)).thenReturn(Flux.just(stuck));
        when(dispatcher.dispatch(stuck)).thenReturn(Mono.error(new IllegalStateException("broker down")));

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();

        verify(outbox).recordFailure(eq(1L), anyString());
        verify(outbox, never()).park(anyLong(), anyString());
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
    @DisplayName("each failed pass doubles the wait before the next, up to max-backoff; a clean one resets it")
    void nextDrainDelay_shouldBackOffAfterFailedPasses_andResetAfterACleanOne() {
        FriendshipOutboxEventEntity first = record(1, 0);
        when(outbox.findPending(100)).thenReturn(Flux.just(first));
        when(dispatcher.dispatch(first)).thenReturn(Mono.error(new IllegalStateException("broker down")));
        assertThat(relay.nextDrainDelay()).isEqualTo(POLL);

        relay.drainOnce().block();
        assertThat(relay.nextDrainDelay()).isEqualTo(POLL.multipliedBy(2));
        relay.drainOnce().block();
        assertThat(relay.nextDrainDelay()).isEqualTo(POLL.multipliedBy(4));
        relay.drainOnce().block();
        relay.drainOnce().block();
        assertThat(relay.nextDrainDelay()).isEqualTo(MAX_BACKOFF);

        when(dispatcher.dispatch(first)).thenReturn(Mono.empty());
        relay.drainOnce().block();
        assertThat(relay.nextDrainDelay()).isEqualTo(POLL);
    }

    @Test
    void nextDrainDelay_shouldBackOff_whenThePendingRecordsCannotBeRead() {
        when(outbox.findPending(100)).thenReturn(Flux.error(new IllegalStateException("graph down")));

        StepVerifier.create(relay.drainOnce()).verifyError(IllegalStateException.class);

        assertThat(relay.nextDrainDelay()).isEqualTo(POLL.multipliedBy(2));
    }

    @Test
    void nextDrainDelay_shouldNotOverflow_afterManyFailedPasses() {
        when(outbox.findPending(100)).thenReturn(Flux.error(new IllegalStateException("graph down")));
        for (int i = 0; i < 100; i++) {
            relay.drainOnce().onErrorResume(error -> Mono.empty()).block();
        }

        assertThat(relay.nextDrainDelay()).isEqualTo(MAX_BACKOFF);
    }

    @Test
    @DisplayName("the purge deletes published and parked records older than the retention window")
    void purgeOnce_shouldDeleteRecordsSettledBeforeTheRetentionWindow() {
        when(outbox.purgeSettledBefore(any(), eq(500))).thenReturn(Mono.just(3L));
        Instant earliest = Instant.now().minus(RETENTION);

        StepVerifier.create(relay.purgeOnce()).expectNext(3L).verifyComplete();

        Instant latest = Instant.now().minus(RETENTION);
        verify(outbox).purgeSettledBefore(argThat(cutoff -> !cutoff.isBefore(earliest) && !cutoff.isAfter(latest)),
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
            verify(outbox, timeout(2000).atLeast(2)).purgeSettledBefore(any(), eq(500));
        } finally {
            relay.stop();
        }
    }

    @Test
    void describe_shouldNameTheRootCause_whenTheErrorIsWrapped() {
        assertThat(FriendshipOutboxRelay.describe(new RuntimeException("send failed",
                new IllegalStateException("broker down"))))
                .isEqualTo("java.lang.RuntimeException: send failed caused by java.lang.IllegalStateException: broker down");
    }

    @Test
    void describe_shouldBeTheErrorItself_whenItHasNoCause() {
        assertThat(FriendshipOutboxRelay.describe(new IllegalStateException("broker down")))
                .isEqualTo("java.lang.IllegalStateException: broker down");
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
