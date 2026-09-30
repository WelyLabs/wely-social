package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.UserNodeEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserNodeMapper {

    UserNodeEntity toUserNodeEntity(UserCreatedEventDTO userCreatedEventDTO);

    UserSocialDTO toUserSocialDTO(UserSocialDBDTO userSocialDBDTO);

    UserNodeDTO toUserNode(UserNodeEntity userNodeEntity);
}
