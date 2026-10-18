package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaFriendshipOutboxDispatcherTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaFriendshipOutboxDispatcher dispatcher = new KafkaFriendshipOutboxDispatcher(streamBridge,
            Mappers.getMapper(FriendshipChangedMapper.class), json);

    private static FriendshipOutboxEventEntity accepted() {
        return new FriendshipOutboxEventEntity(1760779800123L, "ACCEPTED", "bob", "Bob", "alice", "alice:bob",
                "FRIEND", "CLOSE", ZonedDateTime.parse("2026-10-18T09:30:00Z"), ZonedDateTime.parse("2026-10-18T09:30:00.123Z"),
                null, 0L, null);
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
        Message<byte[]> message = dispatcher.message(accepted());

        assertThat(new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8))
                .isEqualTo("alice:bob");
    }

    @Test
    void message_shouldWriteExplicitNulls_whenTheTwoAreNotFriends() {
        FriendshipOutboxEventEntity removed = accepted();
        removed.setType("REMOVED");
        removed.setCloseness(null);
        removed.setSince(null);

        JsonNode payload = json.readTree(dispatcher.message(removed).getPayload());

        assertThat(payload.get("closeness").isNull()).isTrue();
        assertThat(payload.get("since").isNull()).isTrue();
    }

    @Test
    void dispatch_shouldComplete_whenTheBinderTakesTheRecord() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class))).thenReturn(true);

        StepVerifier.create(dispatcher.dispatch(accepted())).verifyComplete();
    }

    @Test
    void dispatch_shouldFail_whenTheBinderRefusesTheRecord() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class))).thenReturn(false);

        StepVerifier.create(dispatcher.dispatch(accepted()))
                .verifyErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorCode())
                        .isEqualTo(TechnicalErrorCode.BROKER_UNAVAILABLE));
    }

    @Test
    void dispatch_shouldFail_whenTheBrokerDoesNotAcknowledge() {
        when(streamBridge.send(eq(KafkaFriendshipOutboxDispatcher.BINDING), any(Message.class)))
                .thenThrow(new IllegalStateException("timeout"));

        StepVerifier.create(dispatcher.dispatch(accepted())).verifyError(IllegalStateException.class);
    }
}
