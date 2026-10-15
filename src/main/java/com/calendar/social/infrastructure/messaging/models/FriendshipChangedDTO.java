package com.calendar.social.infrastructure.messaging.models;

/** Sent on {@code FRIENDSHIP_CHANGED}, keyed by toUserId: the user to notify. */
public record FriendshipChangedDTO(
        String type,
        String fromUserId,
        String fromUserName,
        String toUserId
) {}
