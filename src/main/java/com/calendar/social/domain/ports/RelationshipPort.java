package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RelationshipPort {

    Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO);

    Flux<UserSocialDTO> findAllWithSocialStatus(Long userId);

    Flux<UserNodeDTO> findAllFriends(Long userId);

    Flux<UserNodeDTO> findOutgoingRequests(Long userId);

    Flux<UserNodeDTO> findIncomingRequests(Long userId);

    Mono<UserNodeDTO> sendFriendRequest(Long userId, String targetName, Integer targetHashtag);

    Mono<UserNodeDTO> acceptFriendRequest(Long userId, Long senderId);

    Mono<UserNodeDTO> rejectFriendRequest(Long userId, Long senderId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag);

    Mono<RelationshipDTO> deleteFriendship(Long userId, Long friendId);
}
