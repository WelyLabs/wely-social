package com.calendar.social.domain.models;

import java.time.Instant;

/**
 * Another user as the caller sees them. Closeness, proposal and since are set only between friends.
 *
 * @param since when the friendship was accepted, or null
 */
public record Person(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        RelationStatus relationStatus,
        Closeness closeness,
        Closeness proposal,
        boolean proposedByFriend,
        Instant since
) {}
