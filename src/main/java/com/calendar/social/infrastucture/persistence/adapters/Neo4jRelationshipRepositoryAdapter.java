package com.calendar.social.infrastucture.persistence.adapters;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastucture.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastucture.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastucture.persistence.repositories.UserNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class Neo4jRelationshipRepositoryAdapter implements RelationshipRepository {

    private final com.calendar.social.infrastucture.persistence.repositories.RelationshipRepository relationshipRepository;
    private final RelationshipMapper relationshipMapper;
    private final UserNodeRepository userNodeRepository;
    private final UserNodeMapper userNodeMapper;

    public Neo4jRelationshipRepositoryAdapter(com.calendar.social.infrastucture.persistence.repositories.RelationshipRepository relationshipRepository, RelationshipMapper relationshipMapper, UserNodeRepository userNodeRepository, UserNodeMapper userNodeMapper) {
        this.relationshipRepository = relationshipRepository;
        this.relationshipMapper = relationshipMapper;
        this.userNodeRepository = userNodeRepository;
        this.userNodeMapper = userNodeMapper;
    }

    public Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO) {
        return userNodeRepository.save(userNodeMapper.toUserNodeEntity(userCreatedEventDTO))
                .onErrorMap(e -> {
                    log.error("Erreur lors de l'enregistrement de l'utilisateur : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .then();
    }

    public Flux<UserSocialDTO> findAllWithSocialStatus(String userId) {
        return userNodeRepository.findAllWithSocialStatus(userId)
                .map(userNodeMapper::toUserSocialDTO)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la recherche des utilisateurs : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Flux<UserNodeDTO> findAllFriends(String userId) {
        return userNodeRepository.findAllFriends(userId)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la recherche des amis : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag) {
        return userNodeRepository.existsByUserNameAndHashtag(userName, hashtag)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la vérification de l'existance de l'utilisateur (hashtag) : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Flux<UserNodeDTO> findOutgoingRequests(String userId) {
        return userNodeRepository.findOutgoingRequests(userId)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la recherche des demandes d'amis reçues : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Flux<UserNodeDTO> findIncomingRequests(String userId) {
        return userNodeRepository.findIncomingRequests(userId)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la recherche des demandes d'amis envoyées : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                });
    }

    public Mono<UserNodeDTO> sendFriendRequest(String userId, String targetName, Integer targetHashtag) {
        return userNodeRepository.sendFriendRequest(userId, targetName, targetHashtag)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors de l'envoi de la demande d'amis : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.SEND_FRIEND_REQUEST_FAILURE)));
    }

    public Mono<UserNodeDTO> acceptFriendRequest(String userId, String senderId) {
        return userNodeRepository.acceptFriendRequest(userId, senderId)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors de l'acceptation de la demande d'amis : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.ACCEPT_FRIEND_REQUEST_FAILURE)));
    }

    public Mono<UserNodeDTO> rejectFriendRequest(String userId, String senderId) {
        return userNodeRepository.rejectFriendRequest(userId, senderId)
                .map(userNodeMapper::toUserNode)
                .onErrorMap(e -> {
                    log.error("Erreur lors du rejet de la demande d'amis : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.REJECT_FRIEND_REQUEST_FAILURE)));
    }

    public Mono<RelationshipDTO> deleteFriendship(String userId, Long friendId) {
        return relationshipRepository.deleteFriendship(userId, friendId)
                .map(relationshipMapper::toRelationshipDTO)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la suppression de l'ami : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.DELETE_FRIENDSHIP_FAILURE)));
    }
}
