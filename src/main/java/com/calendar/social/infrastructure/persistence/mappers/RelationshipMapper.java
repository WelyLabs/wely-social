package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RelationshipMapper {

    /** From the column projection the delete query returns; see {@link RelationshipDBDTO}. */
    RelationshipDTO toRelationshipDTO(RelationshipDBDTO relationship);
}
