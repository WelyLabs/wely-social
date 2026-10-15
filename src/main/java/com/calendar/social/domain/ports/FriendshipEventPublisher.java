package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.FriendshipEvent;
import reactor.core.publisher.Mono;

public interface FriendshipEventPublisher {

    Mono<Void> publish(FriendshipEvent event);
}
