package com.calendar.social.application.rest.mappers;

import com.calendar.social.application.rest.dtos.FriendshipResponseDTO;
import com.calendar.social.domain.models.Friendship;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FriendshipRestMapper {

    FriendshipResponseDTO toResponse(Friendship friendship);
}
