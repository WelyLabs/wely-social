package com.calendar.social.domain.models;

import java.time.Instant;
import java.util.List;

/**
 * A community as one viewer sees it.
 *
 * @param role          the viewer's role, null when they are not a member
 * @param friendMembers some of the viewer's friends who are members, at most a handful
 * @param friendMemberCount how many of the viewer's friends are members in all
 * @param pictureKey    the wely-media key of its picture, null when it has none
 */
public record Community(
        String communityId,
        String name,
        String description,
        List<String> interests,
        CommunityVisibility visibility,
        Instant createdAt,
        long memberCount,
        CommunityRole role,
        List<UserNodeDTO> friendMembers,
        long friendMemberCount,
        String pictureKey
) {
    public Community {
        interests = interests == null ? List.of() : List.copyOf(interests);
        friendMembers = friendMembers == null ? List.of() : List.copyOf(friendMembers);
    }

    public boolean isMember() {
        return role != null;
    }

    public boolean isAdmin() {
        return role == CommunityRole.ADMIN;
    }
}
