package com.calendar.social.domain.models;

import java.time.Duration;
import java.time.Instant;

/**
 * An invitation to one person, by one admin. It is single-use and revocable: joining or revoking
 * deletes it. Inviting the same person again replaces it with a fresh one.
 *
 * @param expiresAt exactly {@link #VALIDITY} after it was sent, whatever the time zone
 */
public record CommunityInvitation(
        String token,
        String communityId,
        String communityName,
        CommunityVisibility visibility,
        String inviterId,
        String inviterName,
        String inviteeId,
        String inviteeName,
        Instant createdAt,
        Instant expiresAt
) {
    public static final Duration VALIDITY = Duration.ofDays(3);

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isFor(String userId) {
        return inviteeId.equals(userId);
    }
}
