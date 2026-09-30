package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.RelationshipEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RelationshipMapper {

    RelationshipDTO toRelationshipDTO(RelationshipEntity relationshipEntity);

    /** From the column projection the delete query returns; see {@link RelationshipDBDTO}. */
    RelationshipDTO toRelationshipDTO(RelationshipDBDTO relationship);
}
