package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/** Sends one outbox record to {@code FRIENDSHIP_CHANGED}, keyed by its pair. */
@Component
public class KafkaFriendshipOutboxDispatcher {

    static final String BINDING = "friendship-out-0";

    private final StreamBridge streamBridge;
    private final FriendshipChangedMapper mapper;
    private final ObjectMapper objectMapper;

    public KafkaFriendshipOutboxDispatcher(StreamBridge streamBridge, FriendshipChangedMapper mapper,
                                           ObjectMapper objectMapper) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** Completes once Kafka has the record (the binding is synchronous); any problem arrives as an error. */
    public Mono<Void> dispatch(FriendshipOutboxEventEntity event) {
        return Mono.fromCallable(() -> streamBridge.send(BINDING, message(event)))
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
}
