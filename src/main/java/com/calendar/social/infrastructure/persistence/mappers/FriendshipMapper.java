package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendSummaryDBDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendshipDBDTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FriendshipMapper {

    Friendship toFriendship(FriendshipDBDTO friendship);

    FriendSummary toFriendSummary(FriendSummaryDBDTO summary);
}
