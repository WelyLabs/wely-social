package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.models.UserTag;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.UnaryOperator;

public class RelationshipService {

    static final int MUTUAL_FRIENDS_LIMIT = 50;

    private final RelationshipRepository relationshipRepository;

    public RelationshipService(RelationshipRepository relationshipRepository) {
        this.relationshipRepository = relationshipRepository;
    }

    /**
     * Sends a request by tag. {@code viaEventId} is context only: nothing checks that the two
     * shared that event, since a request by tag reveals nothing more without it.
     */
    public Mono<UserNodeDTO> sendFriendRequest(String userId, String rawUserTag, String viaEventId) {
        UserTag target = UserTag.parse(rawUserTag);

        return relationshipRepository.existsByUserNameAndHashtag(target.userName(), target.hashtag())
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)))
                .flatMap(exists -> relationshipRepository.sendFriendRequest(
                        userId, target.userName(), target.hashtag(), viaEventId));
    }

    public Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId) {
        return relationshipRepository.acceptFriendRequest(userId, senderId);
    }

    public Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId) {
        return relationshipRepository.rejectFriendRequest(userId, senderId);
    }

    public Mono<Void> deleteFriendship(String userId, String friendId) {
        return relationshipRepository.deleteFriendship(userId, friendId).then();
    }

    public Mono<Void> writeUser(UserCreatedEventDTO userCreatedEventDTO) {
        return relationshipRepository.save(userCreatedEventDTO);
    }

    /** A renamed user is found by the new name#hashtag from now on, in requests and searches. */
    public Mono<Void> updateUser(UserModifiedEventDTO userModifiedEventDTO) {
        return relationshipRepository.update(userModifiedEventDTO);
    }

    public Mono<Void> removeUser(UserDeletedEventDTO userDeletedEventDTO) {
        return relationshipRepository.delete(userDeletedEventDTO.userId());
    }

    public Flux<UserResult> readAllWithSocialStatus(String userId, String friendshipsStatus) {

        if (Objects.isNull(friendshipsStatus))
            return relationshipRepository.findAllWithSocialStatus(userId).cast(UserResult.class);

        return switch (friendshipsStatus) {
            case "PENDING_INCOMING" -> relationshipRepository.findIncomingRequests(userId).cast(UserResult.class);
            case "PENDING_OUTGOING" -> relationshipRepository.findOutgoingRequests(userId).cast(UserResult.class);
            case "FRIENDS" -> relationshipRepository.findAllFriends(userId).cast(UserResult.class);
            default -> Flux.empty();
        };
    }

    public Mono<Friendship> findFriendship(String userId, String friendId) {
        return relationshipRepository.findFriendship(userId, friendId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.NOT_FRIENDS)));
    }

    /** Any user's profile with the caller's relationship to them; SELF for the caller's own. */
    public Mono<Person> findPerson(String userId, String personId) {
        return relationshipRepository.findPerson(userId, personId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)));
    }

    /** Open to non-friends too: seeing who you have in common helps decide. */
    public Flux<UserNodeDTO> findMutualFriends(String userId, String otherId) {
        return relationshipRepository.existsById(otherId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)))
                .thenMany(Flux.defer(() ->
                        relationshipRepository.findMutualFriends(userId, otherId, MUTUAL_FRIENDS_LIMIT)));
    }

    public Flux<FriendSummary> listFriendships(String userId) {
        return relationshipRepository.findFriendSummaries(userId);
    }

    public Mono<Friendship> requestCloseness(String userId, String friendId, Closeness target) {
        return change(userId, friendId, friendship -> friendship.requestCloseness(target));
    }

    public Mono<Friendship> acceptCloseness(String userId, String friendId) {
        return change(userId, friendId, Friendship::acceptProposal);
    }

    public Mono<Friendship> declineCloseness(String userId, String friendId) {
        return change(userId, friendId, Friendship::declineProposal);
    }

    /** Read, decide in the domain, then write only if nobody wrote in between. */
    private Mono<Friendship> change(String userId, String friendId, UnaryOperator<Friendship> decision) {
        return findFriendship(userId, friendId).flatMap(current -> {
            Friendship next = decision.apply(current);
            if (next.equals(current)) {
                return Mono.just(current);
            }
            return relationshipRepository.updateCloseness(userId, next, current.version())
                    .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.CLOSENESS_CONFLICT)));
        });
    }
}
