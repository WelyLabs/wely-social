package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.FriendRequest;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RelationshipRepository {

    Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO);

    /** Brings the user's node in line with their new profile; creates it if USER_CREATED was missed. */
    Mono<Void> update(UserModifiedEventDTO userModifiedEventDTO);

    /** Removes the user and every relationship it has. Deleting a missing user is not an error. */
    Mono<Void> delete(String userId);

    Flux<UserSocialDTO> findAllWithSocialStatus(String userId);

    Flux<UserNodeDTO> findAllFriends(String userId);

    Flux<FriendRequest> findOutgoingRequests(String userId);

    Flux<FriendRequest> findIncomingRequests(String userId);

    /** {@code viaEventId} is stored on the pending relationship; null when sent outside an event. */
    Mono<UserNodeDTO> sendFriendRequest(String userId, String targetName, Integer targetHashtag, String viaEventId);

    Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId);

    Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag);

    Mono<RelationshipDTO> deleteFriendship(String userId, String friendId);

    /** The accepted friendship between the two, seen from {@code userId}; empty if they are not friends. */
    Mono<Friendship> findFriendship(String userId, String friendId);

    Flux<FriendSummary> findFriendSummaries(String userId);

    /**
     * Writes the closeness of {@code changed}, provided nobody changed the friendship since
     * {@code expectedVersion} was read; empty otherwise.
     */
    Mono<Friendship> updateCloseness(String userId, Friendship changed, long expectedVersion);
}
