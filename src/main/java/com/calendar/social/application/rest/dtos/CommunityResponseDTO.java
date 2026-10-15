package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserNodeDTO;

import java.time.Instant;
import java.util.List;

/**
 * A community as the caller sees it.
 *
 * @param role null when the caller is not a member
 * @param friendMembers a few of the caller's friends who are members; friendMemberCount says how many in all
 */
public record CommunityResponseDTO(
        String communityId,
        String name,
        String description,
        List<String> interests,
        CommunityVisibility visibility,
        Instant createdAt,
        long memberCount,
        CommunityRole role,
        List<UserNodeDTO> friendMembers,
        long friendMemberCount
) {}
