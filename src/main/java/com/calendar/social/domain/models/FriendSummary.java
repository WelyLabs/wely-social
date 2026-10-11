package com.calendar.social.domain.models;

/** A friend with the state of the friendship, as listed for the caller. */
public record FriendSummary(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        Closeness closeness,
        Closeness proposal,
        boolean proposedByFriend
) {}
