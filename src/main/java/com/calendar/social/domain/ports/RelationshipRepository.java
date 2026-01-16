package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RelationshipRepository {

    Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO);

    Flux<UserSocialDTO> findAllWithSocialStatus(String userId);

    Flux<UserNodeDTO> findAllFriends(String userId);

    Flux<UserNodeDTO> findOutgoingRequests(String userId);

    Flux<UserNodeDTO> findIncomingRequests(String userId);

    Mono<UserNodeDTO> sendFriendRequest(String userId, String targetName, Integer targetHashtag);

    Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId);

    Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag);

    Mono<RelationshipDTO> deleteFriendship(String userId, Long friendId);
}
