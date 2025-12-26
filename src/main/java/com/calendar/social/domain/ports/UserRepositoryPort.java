package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepositoryPort {

    Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO);

    Flux<UserSocialDTO> findAllWithSocialStatus(Long userId);

    Flux<UserNodeDTO> findAllFriends(Long userId);

    Flux<UserNodeDTO> findOutgoingRequests(Long userId);

    Flux<UserNodeDTO> findIncomingRequests(Long userId);

    Mono<UserNodeDTO> sendFriendRequest(Long userId, String targetName, Integer targetHashtag);
}
