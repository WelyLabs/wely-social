package com.calendar.social.domain.models;

/** A pending request, with the event it was sent from when there was one. */
public record FriendRequest(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String viaEventId
) implements UserResult {}
