package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipStateMapper;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaFriendshipOutboxDispatcherTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaFriendshipOutboxDispatcher dispatcher = new KafkaFriendshipOutboxDispatcher(streamBridge,
            Mappers.getMapper(FriendshipChangedMapper.class), Mappers.getMapper(FriendshipStateMapper.class), json);

    private static FriendshipOutboxEventEntity accepted() {
        return new FriendshipOutboxEventEntity(1760779800123L, "ACCEPTED", "bob", "Bob", "alice", "alice:bob",
                "FRIEND", "CLOSE", ZonedDateTime.parse("2026-10-18T09:30:00Z"), ZonedDateTime.parse("2026-10-18T09:30:00.123Z"),
                null, 0L, null, null);
    }

    private static FriendshipOutboxEventEntity ofType(String type) {
        FriendshipOutboxEventEntity event = accepted();
        event.setType(type);
        if (!KafkaFriendshipOutboxDispatcher.FRIENDS_TYPES.contains(type)) {
            event.setCloseness(null);
            event.setSince(null);
        }
        return event;
    }

    private static String key(Message<?> message) {
        return new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8);
    }

    @Test
    void message_shouldCarryTheContractsJson_withIsoInstants() {
        Message<byte[]> message = dispatcher.message(accepted());

        JsonNode payload = json.readTree(message.getPayload());
        assertThat(payload.get("type").asString()).isEqualTo("ACCEPTED");
        assertThat(payload.get("fromUserId").asString()).isEqualTo("bob");
        assertThat(payload.get("fromUserName").asString()).isEqualTo("Bob");
        assertThat(payload.get("toUserId").asString()).isEqualTo("alice");
        assertThat(payload.get("closeness").asString()).isEqualTo("FRIEND");
        assertThat(payload.get("previousCloseness").asString()).isEqualTo("CLOSE");
        assertThat(payload.get("since").asString()).isEqualTo("2026-10-18T09:30:00Z");
        assertThat(payload.get("version").asLong()).isEqualTo(1760779800123L);
        assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-10-18T09:30:00.123Z");
    }

    @Test
    void message_shouldBeKeyedByThePair() {
        assertThat(key(dispatcher.message(accepted()))).isEqualTo("alice:bob");
    }

    @Test
    void message_shouldWriteExplicitNulls_whenTheTwoAreNotFriends() {
        JsonNode payload = json.readTree(dispatcher.message(ofType("REMOVED")).getPayload());

        assertThat(payload.get("closeness").isNull()).isTrue();
        assertThat(payload.get("since").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "CLOSENESS_CHANGED", "SNAPSHOT"})
    void stateMessage_shouldCarryTheFriendship_keyedBySortedIds_whenTheTwoAreFriends(String type) {
        Message<?> state = dispatcher.stateMessage(ofType(type)).orElseThrow();

        assertThat(key(state)).isEqualTo("alice:bob");
        JsonNode payload = json.readTree((byte[]) state.getPayload());
        assertThat(payload.size()).isEqualTo(6);
        assertThat(payload.get("a").asString()).isEqualTo("alice");
        assertThat(payload.get("b").asString()).isEqualTo("bob");
        assertThat(payload.get("friends").asBoolean()).isTrue();
        assertThat(payload.get("closeness").asString()).isEqualTo("FRIEND");
        assertThat(payload.get("since").asString()).isEqualTo("2026-10-18T09:30:00Z");
        assertThat(payload.get("version").asLong()).isEqualTo(1760779800123L);
    }

    @Test
    void stateMessage_shouldBeATombstone_keyedBySortedIds_whenTheFriendshipEnded() {
        Message<?> state = dispatcher.stateMessage(ofType("REMOVED")).orElseThrow();

        assertThat(state.getPayload()).isSameAs(KafkaNull.INSTANCE);
        assertThat(key(state)).isEqualTo("alice:bob");
    }

    @Test
    void stateMessage_shouldBeATombstone_whenAFriendsTypeFoundNoFriendshipInTheGraph() {
        FriendshipOutboxEventEntity event = accepted();
        event.setCloseness(null);

        assertThat(dispatcher.stateMessage(event).orElseThrow().getPayload()).isSameAs(KafkaNull.INSTANCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "REJECTED", "CANCELLED"})
    void stateMessage_shouldBeEmpty_whenTheRecordDoesNotChangeTheFriendship(String type) {
        assertThat(dispatcher.stateMessage(ofType(type))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_shouldSendTheChange_thenTheState_whenTheBinderTakesBoth() {
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

        StepVerifier.create(dispatcher.dispatch(accepted())).expectComplete().verify(Duration.ofSeconds(10));

        ArgumentCaptor<Message<?>> messages = ArgumentCaptor.forClass(Message.class);
        InOrder order = inOrder(streamBridge);
        order.verify(streamBridge).send(eq(KafkaFriendshipOutboxDispatcher.BINDING), messages.capture());
        order.verify(streamBridge).send(eq(KafkaFriendshipOutboxDispatcher.STATE_BINDING), messages.capture());
        assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(key(message)).isEqualTo("alice:bob"));
    }

    @Test
    void dispatch_shouldSendOnlyTheChange_whenTheRecordDoesNotChangeTheFriendship() {
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

        StepVerifier.create(dispatcher.dispatch(ofType("REQUESTED"))).expectComplete().verify(Duration.ofSeconds(10));

        verify(streamBridge).send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class));
        verify(streamBridge, never()).send(eq(KafkaFriendshipOutboxDispatcher.STATE_BINDING), any(Message.class));
    }

    @Test
    void dispatch_shouldFail_whenTheBinderRefusesTheChange_andNotSendTheState() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class))).thenReturn(false);

        StepVerifier.create(dispatcher.dispatch(accepted()))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorCode())
                        .isEqualTo(TechnicalErrorCode.BROKER_UNAVAILABLE)).verify(Duration.ofSeconds(10));

        verify(streamBridge, never()).send(eq(KafkaFriendshipOutboxDispatcher.STATE_BINDING), any(Message.class));
    }

    @Test
    void dispatch_shouldFail_whenTheBinderRefusesTheState() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class))).thenReturn(true);
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.STATE_BINDING), any(Message.class))).thenReturn(false);

        StepVerifier.create(dispatcher.dispatch(accepted()))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorCode())
                        .isEqualTo(TechnicalErrorCode.BROKER_UNAVAILABLE)).verify(Duration.ofSeconds(10));
    }

    @Test
    void dispatch_shouldFail_whenTheBrokerDoesNotAcknowledge() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class)))
                .thenThrow(new IllegalStateException("timeout"));

        StepVerifier.create(dispatcher.dispatch(accepted())).expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void dispatch_shouldFailAsUnpublishable_andSendNothing_whenAMessageCannotBeBuilt() {
        FriendshipChangedMapper brokenMapper = mock(FriendshipChangedMapper.class);
        when(brokenMapper.toDto(any())).thenThrow(new IllegalArgumentException("unmappable"));
        KafkaFriendshipOutboxDispatcher broken = new KafkaFriendshipOutboxDispatcher(streamBridge, brokenMapper,
                Mappers.getMapper(FriendshipStateMapper.class), json);

        StepVerifier.create(broken.dispatch(accepted()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(UnpublishableRecordException.class)
                        .hasMessageContaining("1760779800123")
                        .hasCauseInstanceOf(IllegalArgumentException.class)).verify(Duration.ofSeconds(10));

        verify(streamBridge, never()).send(anyString(), any(Message.class));
    }

    @Test
    void dispatch_shouldFailAsUnpublishable_whenTheRecordHasNoPairKey() {
        FriendshipOutboxEventEntity event = accepted();
        event.setPairKey(null);

        StepVerifier.create(dispatcher.dispatch(event)).expectError(UnpublishableRecordException.class).verify(Duration.ofSeconds(10));

        verify(streamBridge, never()).send(anyString(), any(Message.class));
    }
}
