package com.calendar.social.domain.models;

import java.time.Instant;

/** A member of a community, as its other members see them. */
public record CommunityMember(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        CommunityRole role,
        Instant joinedAt
) {}
