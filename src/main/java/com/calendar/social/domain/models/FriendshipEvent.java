package com.calendar.social.domain.models;

/** A change in a friendship that toUser should hear about. fromUserName is the bare name, without hashtag. */
public record FriendshipEvent(
        FriendshipEventType type,
        String fromUserId,
        String fromUserName,
        String toUserId
) {}
