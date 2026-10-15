package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityInvitationTest {

    private static final Instant SENT = Instant.parse("2026-10-15T10:00:00Z");
    private static final CommunityInvitation INVITATION = new CommunityInvitation("t", "c", "Club",
            CommunityVisibility.PRIVATE, "admin", "admin", "invitee", "invitee", SENT, SENT.plus(CommunityInvitation.VALIDITY));

    @Test
    void validity_shouldBeThreeDays() {
        assertThat(CommunityInvitation.VALIDITY).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void isExpiredAt_shouldTurnTrueAtTheExactExpiry() {
        assertThat(INVITATION.isExpiredAt(SENT)).isFalse();
        assertThat(INVITATION.isExpiredAt(SENT.plus(CommunityInvitation.VALIDITY).minusMillis(1))).isFalse();
        assertThat(INVITATION.isExpiredAt(SENT.plus(CommunityInvitation.VALIDITY))).isTrue();
        assertThat(INVITATION.isExpiredAt(SENT.plus(Duration.ofDays(4)))).isTrue();
    }

    @Test
    void isFor_shouldNameTheInviteeOnly() {
        assertThat(INVITATION.isFor("invitee")).isTrue();
        assertThat(INVITATION.isFor("admin")).isFalse();
    }
}
