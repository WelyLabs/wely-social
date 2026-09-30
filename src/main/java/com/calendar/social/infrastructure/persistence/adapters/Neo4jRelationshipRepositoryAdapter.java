package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.infrastructure.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastructure.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastructure.persistence.repositories.RelationshipNeo4jRepository;
import com.calendar.social.infrastructure.persistence.repositories.UserNodeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Drives the social graph through explicit Cypher.
 *
 * <p>No error handling here. Logging a failure and translating it into
 * {@code TechnicalException(DATABASE_ERROR)} used to be repeated in all ten methods; that
 * now lives in {@link com.calendar.social.infrastructure.observability.PersistenceErrorAspect},
 * which wraps each returned publisher. What stays in this class is what actually differs
 * per method: which business failure an empty result stands for.
 */
@Component
public class Neo4jRelationshipRepositoryAdapter implements RelationshipRepository {

    private final RelationshipNeo4jRepository relationshipRepository;
    private final RelationshipMapper relationshipMapper;
    private final UserNodeRepository userNodeRepository;
    private final UserNodeMapper userNodeMapper;

    public Neo4jRelationshipRepositoryAdapter(
            RelationshipNeo4jRepository relationshipRepository,
            RelationshipMapper relationshipMapper,
            UserNodeRepository userNodeRepository,
            UserNodeMapper userNodeMapper) {
        this.relationshipRepository = relationshipRepository;
        this.relationshipMapper = relationshipMapper;
        this.userNodeRepository = userNodeRepository;
        this.userNodeMapper = userNodeMapper;
    }

    @Override
    public Mono<Void> save(UserCreatedEventDTO event) {
        return userNodeRepository
                .upsert(event.userId(), event.userName(), event.hashtag(), event.profilePicUrl())
                .then();
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
    public Flux<UserNodeDTO> findOutgoingRequests(String userId) {
        return userNodeRepository.findOutgoingRequests(userId).map(userNodeMapper::toUserNode);
    }

    @Override
    public Flux<UserNodeDTO> findIncomingRequests(String userId) {
        return userNodeRepository.findIncomingRequests(userId).map(userNodeMapper::toUserNode);
    }

    @Override
    public Mono<UserNodeDTO> sendFriendRequest(String userId, String targetName, Integer targetHashtag) {
        return userNodeRepository
                .sendFriendRequest(userId, targetName, targetHashtag)
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
        return relationshipRepository
                .deleteFriendship(userId, friendId)
                .map(relationshipMapper::toRelationshipDTO)
                .switchIfEmpty(Mono.error(
                        new BusinessException(BusinessErrorCode.DELETE_FRIENDSHIP_FAILURE)));
    }
}
