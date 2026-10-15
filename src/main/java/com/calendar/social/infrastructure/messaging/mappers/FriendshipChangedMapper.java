package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FriendshipChangedMapper {

    FriendshipChangedDTO toDto(FriendshipEvent event);
}
