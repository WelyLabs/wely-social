package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.FriendRequest;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.infrastructure.persistence.mappers.FriendshipMapper;
import com.calendar.social.infrastructure.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastructure.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastructure.persistence.repositories.UserNodeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Users and friendships in the social graph; failures are translated by {@code PersistenceErrorAspect}. */
@Component
public class Neo4jRelationshipRepositoryAdapter implements RelationshipRepository {

    private final RelationshipMapper relationshipMapper;
    private final UserNodeRepository userNodeRepository;
    private final UserNodeMapper userNodeMapper;
    private final FriendshipMapper friendshipMapper;

    public Neo4jRelationshipRepositoryAdapter(
            RelationshipMapper relationshipMapper,
            UserNodeRepository userNodeRepository,
            UserNodeMapper userNodeMapper,
            FriendshipMapper friendshipMapper) {
        this.relationshipMapper = relationshipMapper;
        this.userNodeRepository = userNodeRepository;
        this.userNodeMapper = userNodeMapper;
        this.friendshipMapper = friendshipMapper;
    }

    @Override
    public Mono<Void> save(UserCreatedEventDTO event) {
        return userNodeRepository
                .upsert(event.userId(), event.userName(), event.hashtag(), event.profilePicUrl())
                .then();
    }

    /** The same guarded MERGE as {@link #save}: heals a node USER_CREATED never made, never a deleted user's. */
    @Override
    public Mono<Void> update(UserModifiedEventDTO event) {
        return userNodeRepository
                .upsert(event.userId(), event.userName(), event.hashtag(), event.profilePicUrl())
                .then();
    }

    @Override
    public Mono<Void> delete(String userId) {
        return userNodeRepository.deleteUser(userId);
    }

    @Override
    public Flux<UserSocialDTO> findAllWithSocialStatus(String userId) {
        return userNodeRepository.findAllWithSocialStatus(userId).map(userNodeMapper::toUserSocialDTO);
    }

    @Override
    public Flux<UserNodeDTO> findAllFriends(String userId) {
        return userNodeRepository.findAllFriends(userId).map(userNodeMapper::toUserNode);
    }

    @Override
    public Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag) {
        return userNodeRepository.existsByUserNameAndHashtag(userName, hashtag);
    }

    @Override
    public Flux<FriendRequest> findOutgoingRequests(String userId) {
        return userNodeRepository.findOutgoingRequests(userId).map(userNodeMapper::toFriendRequest);
    }

    @Override
    public Flux<FriendRequest> findIncomingRequests(String userId) {
        return userNodeRepository.findIncomingRequests(userId).map(userNodeMapper::toFriendRequest);
    }

    @Override
    public Mono<UserNodeDTO> sendFriendRequest(String userId, String targetName, Integer targetHashtag,
                                               String viaEventId) {
        return userNodeRepository
                .sendFriendRequest(userId, targetName, targetHashtag, viaEventId)
                .map(userNodeMapper::toUserNode)
                // Empty means the Cypher matched nothing: no such user, or the two are
                // already related.
                .switchIfEmpty(Mono.error(
                        new BusinessException(BusinessErrorCode.SEND_FRIEND_REQUEST_FAILURE)));
    }

    @Override
    public Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId) {
        return userNodeRepository
                .acceptFriendRequest(userId, senderId)
                .map(userNodeMapper::toUserNode)
                .switchIfEmpty(Mono.error(
                        new BusinessException(BusinessErrorCode.ACCEPT_FRIEND_REQUEST_FAILURE)));
    }

    @Override
    public Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId) {
        return userNodeRepository
                .rejectFriendRequest(userId, senderId)
                .map(userNodeMapper::toUserNode)
                .switchIfEmpty(Mono.error(
                        new BusinessException(BusinessErrorCode.REJECT_FRIEND_REQUEST_FAILURE)));
    }

    @Override
    public Mono<RelationshipDTO> deleteFriendship(String userId, String friendId) {
        return userNodeRepository
                .deleteFriendship(userId, friendId)
                .map(relationshipMapper::toRelationshipDTO)
                .switchIfEmpty(Mono.error(
                        new BusinessException(BusinessErrorCode.DELETE_FRIENDSHIP_FAILURE)));
    }

    @Override
    public Mono<Friendship> findFriendship(String userId, String friendId) {
        return userNodeRepository.findFriendship(userId, friendId).map(friendshipMapper::toFriendship);
    }

    @Override
    public Flux<FriendSummary> findFriendSummaries(String userId) {
        return userNodeRepository.findFriendSummaries(userId).map(friendshipMapper::toFriendSummary);
    }

    @Override
    public Mono<Friendship> updateCloseness(String userId, Friendship changed, long expectedVersion) {
        // The domain says who proposed relative to the caller; the graph stores the userId.
        String proposedBy = changed.proposal() == null ? null
                : changed.proposedByFriend() ? changed.friendId() : userId;
        return userNodeRepository
                .updateCloseness(userId, changed.friendId(), changed.closeness().name(),
                        changed.proposal() == null ? null : changed.proposal().name(),
                        proposedBy, expectedVersion)
                .map(friendshipMapper::toFriendship);
    }

    @Override
    public Mono<Boolean> existsById(String userId) {
        return userNodeRepository.existsById(userId);
    }

    @Override
    public Mono<Person> findPerson(String userId, String personId) {
        return userNodeRepository.findPerson(userId, personId).map(userNodeMapper::toPerson);
    }

    @Override
    public Flux<UserNodeDTO> findMutualFriends(String userId, String otherId, int limit) {
        return userNodeRepository.findMutualFriends(userId, otherId, limit).map(userNodeMapper::toUserNode);
    }
}
