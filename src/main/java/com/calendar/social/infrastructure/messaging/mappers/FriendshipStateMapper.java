package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.infrastructure.messaging.models.FriendshipStateDTO;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.time.ZonedDateTime;

/** An outbox record as the pair's state on {@code FRIENDSHIP_STATE}: ids sorted, so each pair has one key. */
@Mapper(componentModel = "spring")
public interface FriendshipStateMapper {

    String KEY_SEPARATOR = ":";

    @Mapping(target = "a", expression = "java(FriendshipStateMapper.lowerId(event))")
    @Mapping(target = "b", expression = "java(FriendshipStateMapper.higherId(event))")
    @Mapping(target = "friends", constant = "true")
    FriendshipStateDTO toDto(FriendshipOutboxEventEntity event);

    default Instant toInstant(ZonedDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }

    static String lowerId(FriendshipOutboxEventEntity event) {
        return event.getFromUserId().compareTo(event.getToUserId()) <= 0 ? event.getFromUserId() : event.getToUserId();
    }

    static String higherId(FriendshipOutboxEventEntity event) {
        return event.getFromUserId().compareTo(event.getToUserId()) <= 0 ? event.getToUserId() : event.getFromUserId();
    }

    /** The compaction key: {@code a:b}, the same whichever user acted. */
    static String stateKey(FriendshipOutboxEventEntity event) {
        return lowerId(event) + KEY_SEPARATOR + higherId(event);
    }
}
