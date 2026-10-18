package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.FriendshipEvent;
import reactor.core.publisher.Mono;

/**
 * Friendship changes waiting to be published. {@link #append} joins the caller's transaction, so the
 * record commits with the change or not at all; a relay publishes it afterwards.
 */
public interface FriendshipEventOutbox {

    Mono<Void> append(FriendshipEvent event);
}
