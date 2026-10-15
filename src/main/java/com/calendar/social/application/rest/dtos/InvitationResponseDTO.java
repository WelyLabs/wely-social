package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.CommunityVisibility;

import java.time.Instant;

/** @param expired whether the invitation can no longer be used; it still reads, so the page can say so */
public record InvitationResponseDTO(
        String token,
        String communityId,
        String communityName,
        CommunityVisibility visibility,
        String inviterId,
        String inviterName,
        String inviteeId,
        String inviteeName,
        Instant createdAt,
        Instant expiresAt,
        boolean expired
) {}
