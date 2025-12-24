package com.calendar.social.infrastucture.mappers;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.infrastucture.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastucture.models.entities.UserNodeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserNodeMapper {

    @Mapping(target = "id", source = "userId")
    UserNodeEntity toUserNodeEntity(UserCreatedEventDTO userCreatedEventDTO);

    UserSocialDTO toUserSocialDTO(UserSocialDBDTO userSocialDBDTO);
}
