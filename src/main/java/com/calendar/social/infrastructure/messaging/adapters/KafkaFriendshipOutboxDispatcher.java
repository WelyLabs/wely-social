package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipStateMapper;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sends one outbox record to {@code FRIENDSHIP_CHANGED}, keyed by its pair, then, when it changes
 * whether or how the two are friends, the pair's state to the compacted {@code FRIENDSHIP_STATE}.
 */
@Component
public class KafkaFriendshipOutboxDispatcher {

    static final String BINDING = "friendship-out-0";
    static final String STATE_BINDING = "friendship-state-out-0";
    /** Types after which the pair is friends: their state record carries the friendship. */
    static final Set<String> FRIENDS_TYPES = Set.of("ACCEPTED", "CLOSENESS_CHANGED", "SNAPSHOT");
    /** The type after which the pair is no longer friends: its state record is a tombstone. */
    static final String REMOVED_TYPE = "REMOVED";

    private final StreamBridge streamBridge;
    private final FriendshipChangedMapper mapper;
    private final FriendshipStateMapper stateMapper;
    private final ObjectMapper objectMapper;

    public KafkaFriendshipOutboxDispatcher(StreamBridge streamBridge, FriendshipChangedMapper mapper,
                                           FriendshipStateMapper stateMapper, ObjectMapper objectMapper) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
        this.stateMapper = stateMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Completes once Kafka has every message of the record (the bindings are synchronous); any problem
     * arrives as an error. A record whose messages cannot be built fails with
     * {@link UnpublishableRecordException}, before anything is sent.
     */
    public Mono<Void> dispatch(FriendshipOutboxEventEntity event) {
        return Mono.fromCallable(() -> outgoing(event))
                .onErrorMap(error -> new UnpublishableRecordException(event.getVersion(), error))
                // In order: the change first, then the state it leads to.
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::send)
                .then();
    }

    private List<Outgoing> outgoing(FriendshipOutboxEventEntity event) {
        List<Outgoing> messages = new ArrayList<>(2);
        messages.add(new Outgoing(BINDING, message(event)));
        stateMessage(event).ifPresent(state -> messages.add(new Outgoing(STATE_BINDING, state)));
        return messages;
    }

    private Mono<Void> send(Outgoing outgoing) {
        return Mono.fromCallable(() -> streamBridge.send(outgoing.binding(), outgoing.message()))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)));
    }

    /** Serialized here, so the record's JSON (ISO instants included) does not depend on the binder's converter. */
    Message<byte[]> message(FriendshipOutboxEventEntity event) {
        return MessageBuilder.withPayload(objectMapper.writeValueAsBytes(mapper.toDto(event)))
                .setHeader(KafkaHeaders.KEY, event.getPairKey().getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * The pair's state after the record, keyed by {@code a:b}: the friendship, or a tombstone (a
     * {@link KafkaNull} payload, sent as a null value) once they are not friends. Empty for a record
     * that does not change it: a request, a rejection, a withdrawal.
     */
    Optional<Message<?>> stateMessage(FriendshipOutboxEventEntity event) {
        boolean friendsType = FRIENDS_TYPES.contains(event.getType());
        if (!friendsType && !REMOVED_TYPE.equals(event.getType())) {
            return Optional.empty();
        }
        byte[] key = FriendshipStateMapper.stateKey(event).getBytes(StandardCharsets.UTF_8);
        // No closeness means the graph held no friendship when the record was written: not friends.
        if (!friendsType || event.getCloseness() == null) {
            return Optional.of(MessageBuilder.withPayload(KafkaNull.INSTANCE)
                    .setHeader(KafkaHeaders.KEY, key)
                    .build());
        }
        return Optional.of(MessageBuilder.withPayload(objectMapper.writeValueAsBytes(stateMapper.toDto(event)))
                .setHeader(KafkaHeaders.KEY, key)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON_VALUE)
                .build());
    }

    private record Outgoing(String binding, Message<?> message) {}
}
