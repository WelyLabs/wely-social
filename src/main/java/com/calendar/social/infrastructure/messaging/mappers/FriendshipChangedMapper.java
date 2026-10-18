package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.mapstruct.Mapper;

import java.time.Instant;
import java.time.ZonedDateTime;

@Mapper(componentModel = "spring")
public interface FriendshipChangedMapper {

    FriendshipChangedDTO toDto(FriendshipOutboxEventEntity event);

    default Instant toInstant(ZonedDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
