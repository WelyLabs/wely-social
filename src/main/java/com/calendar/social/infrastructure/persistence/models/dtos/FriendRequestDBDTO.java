package com.calendar.social.infrastructure.persistence.models.dtos;

/** Column projection of a pending request: the other user, plus the event it came from. */
public record FriendRequestDBDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String viaEventId
) {}
