package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.ports.FriendshipEventPublisher;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sends friendship changes to {@code FRIENDSHIP_CHANGED}. */
@Slf4j
@Component
public class KafkaFriendshipEventPublisher implements FriendshipEventPublisher {

    static final String BINDING = "friendship-out-0";

    private final StreamBridge streamBridge;
    private final FriendshipChangedMapper mapper;

    public KafkaFriendshipEventPublisher(StreamBridge streamBridge, FriendshipChangedMapper mapper) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
    }

    /** A refused record is logged, not raised; a binder failure becomes a {@link TechnicalException}. */
    @Override
    public Mono<Void> publish(FriendshipEvent event) {
        FriendshipChangedDTO dto = mapper.toDto(event);
        return Mono.fromCallable(() -> streamBridge.send(BINDING, dto))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(sent -> {
                    if (!Boolean.TRUE.equals(sent)) {
                        log.error("FRIENDSHIP_CHANGED refused by the binder: type={}, from={}, to={}",
                                dto.type(), dto.fromUserId(), dto.toUserId());
                    }
                })
                .onErrorMap(error -> !(error instanceof TechnicalException), error -> {
                    log.error("FRIENDSHIP_CHANGED not sent: type={}, from={}, to={}",
                            dto.type(), dto.fromUserId(), dto.toUserId(), error);
                    return new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE);
                })
                .then();
    }
}
