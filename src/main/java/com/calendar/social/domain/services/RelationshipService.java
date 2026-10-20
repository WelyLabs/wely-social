package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.models.UserTag;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.SocialActivity;
import com.calendar.social.domain.ports.TransactionRunner;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.UnaryOperator;

public class RelationshipService {

    static final int MUTUAL_FRIENDS_LIMIT = 50;

    private final RelationshipRepository relationshipRepository;
    private final FriendshipEventOutbox outbox;
    private final TransactionRunner transactions;
    private final SocialActivity activity;

    public RelationshipService(RelationshipRepository relationshipRepository, FriendshipEventOutbox outbox,
                               TransactionRunner transactions, SocialActivity activity) {
        this.relationshipRepository = relationshipRepository;
        this.outbox = outbox;
        this.transactions = transactions;
        this.activity = activity;
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
                .flatMap(exists -> transactions.inTransaction(Mono.defer(() -> relationshipRepository
                                .sendFriendRequest(userId, target.userName(), target.hashtag(), viaEventId))
                        .flatMap(receiver -> record(FriendshipEvent.betweenNonFriends(
                                        FriendshipEventType.REQUESTED, userId, receiver.userId()))
                                .thenReturn(receiver))))
                // Once committed, and only for a stored request: an empty answer stored nothing.
                .doOnNext(receiver -> activity.friendRequestSent());
    }

    /** The event goes from the acceptor to the original requester, as wely-notifications expects. */
    public Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId) {
        return transactions.inTransaction(Mono.defer(() -> relationshipRepository.acceptFriendRequest(userId, senderId))
                .flatMap(sender -> record(FriendshipEvent.betweenNonFriends(
                        FriendshipEventType.ACCEPTED, userId, sender.userId()))
                        .thenReturn(sender)))
                .doOnNext(sender -> activity.friendRequestAccepted());
    }

    public Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId) {
        return transactions.inTransaction(Mono.defer(() -> relationshipRepository.rejectFriendRequest(userId, senderId))
                .flatMap(sender -> record(FriendshipEvent.betweenNonFriends(
                        FriendshipEventType.REJECTED, userId, sender.userId()))
                        .thenReturn(sender)))
                .doOnNext(sender -> activity.friendRequestDeclined());
    }

    public Mono<Void> deleteFriendship(String userId, String friendId) {
        return transactions.inTransaction(Mono.defer(() -> relationshipRepository.deleteFriendship(userId, friendId))
                .flatMap(removed -> record(new FriendshipEvent(FriendshipEventType.REMOVED, userId, friendId,
                        removed.closeness()))));
    }

    public Mono<Void> writeUser(UserCreatedEventDTO userCreatedEventDTO) {
        return relationshipRepository.save(userCreatedEventDTO);
    }

    /** A renamed user is found by the new name#hashtag from now on, in requests and searches. */
    public Mono<Void> updateUser(UserModifiedEventDTO userModifiedEventDTO) {
        return relationshipRepository.update(userModifiedEventDTO);
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

    /** Joins the caller's transaction: the record commits with the change it describes. */
    private Mono<Void> record(FriendshipEvent event) {
        return Mono.defer(() -> outbox.append(event));
    }

    /**
     * Read, decide in the domain, then write only if nobody wrote in between. A closeness that takes
     * effect is recorded with the write; a mere proposal is not.
     */
    private Mono<Friendship> change(String userId, String friendId, UnaryOperator<Friendship> decision) {
        return findFriendship(userId, friendId).flatMap(current -> {
            Friendship next = decision.apply(current);
            if (next.equals(current)) {
                return Mono.just(current);
            }
            boolean takesEffect = next.closeness() != current.closeness();
            return transactions.inTransaction(Mono.defer(() ->
                            relationshipRepository.updateCloseness(userId, next, current.version()))
                    .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.CLOSENESS_CONFLICT)))
                    .flatMap(written -> takesEffect
                            ? record(new FriendshipEvent(FriendshipEventType.CLOSENESS_CHANGED, userId, friendId,
                                    current.closeness())).thenReturn(written)
                            : Mono.just(written)));
        });
    }
}
