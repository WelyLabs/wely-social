package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.CommunityRole;

import java.time.Instant;

public record CommunityMemberResponseDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        CommunityRole role,
        Instant joinedAt
) {}
