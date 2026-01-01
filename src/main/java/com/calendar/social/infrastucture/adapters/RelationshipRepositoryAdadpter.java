package com.calendar.social.infrastucture.adapters;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.ports.RelationshipRepositoryPort;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastucture.mappers.RelationshipMapper;
import com.calendar.social.infrastucture.repositories.RelationshipRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RelationshipRepositoryAdadpter implements RelationshipRepositoryPort {

    private final RelationshipRepository relationshipRepository;
    private final RelationshipMapper relationshipMapper;

    public RelationshipRepositoryAdadpter(RelationshipRepository relationshipRepository, RelationshipMapper relationshipMapper) {
        this.relationshipRepository = relationshipRepository;
        this.relationshipMapper = relationshipMapper;
    }

    public Mono<RelationshipDTO> deleteFriendship(Long userId, Long friendId) {
        return relationshipRepository.deleteFriendship(userId, friendId)
                .map(relationshipMapper::toRelationshipDTO)
                .onErrorMap(e -> {
                    log.error("Erreur lors de la suppression de l'ami : {}", e.getMessage());
                    return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
                })
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.DELETE_FRIENDSHIP_FAILURE)));
    }
}
