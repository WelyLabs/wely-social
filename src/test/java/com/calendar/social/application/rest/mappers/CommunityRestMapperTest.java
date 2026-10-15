package com.calendar.social.application.rest.mappers;

import com.calendar.social.application.rest.dtos.CommunityRequestDTO;
import com.calendar.social.application.rest.dtos.InvitationResponseDTO;
import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserNodeDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityRestMapperTest {

    private static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
    private final CommunityRestMapper mapper = Mappers.getMapper(CommunityRestMapper.class);

    @Test
    void toDraft_shouldNormalizeThroughTheDomain() {
        CommunityDraft draft = mapper.toDraft(new CommunityRequestDTO(" Club ", null, List.of("Auto"), CommunityVisibility.PUBLIC));

        assertThat(draft).isEqualTo(new CommunityDraft("Club", null, List.of("auto"), CommunityVisibility.PUBLIC));
    }

    @Test
    void toDraft_shouldMapNoRequestToNull() {
        assertThat(mapper.toDraft(null)).isNull();
    }

    @Test
    void toResponse_shouldCopyACommunity() {
        UserNodeDTO friend = new UserNodeDTO("f", "lea", 1, null);
        var response = mapper.toResponse(new Community("c", "Club", "d", List.of("auto"), CommunityVisibility.PRIVATE, T, 3,
                CommunityRole.ADMIN, List.of(friend), 1));

        assertThat(response.communityId()).isEqualTo("c");
        assertThat(response.role()).isEqualTo(CommunityRole.ADMIN);
        assertThat(response.friendMembers()).containsExactly(friend);
        assertThat(response.memberCount()).isEqualTo(3);
        assertThat(response.friendMemberCount()).isEqualTo(1);
    }

    @Test
    void toResponse_shouldCopyAMember() {
        var response = mapper.toResponse(new CommunityMember("u", "lea", 1, "/p", CommunityRole.MEMBER, T));

        assertThat(response.userId()).isEqualTo("u");
        assertThat(response.role()).isEqualTo(CommunityRole.MEMBER);
        assertThat(response.joinedAt()).isEqualTo(T);
    }

    @Test
    void toResponse_shouldCopyALeave() {
        var response = mapper.toResponse(CommunityLeave.handedOverTo("u"));

        assertThat(response.communityDeleted()).isFalse();
        assertThat(response.newAdminId()).isEqualTo("u");
    }

    @Test
    void toResponse_shouldSayWhetherAnInvitationExpired_atTheGivenInstant() {
        CommunityInvitation invitation = new CommunityInvitation("t", "c", "Club", CommunityVisibility.PRIVATE, "a", "admin",
                "i", "invitee", T, T.plus(CommunityInvitation.VALIDITY));

        InvitationResponseDTO live = mapper.toResponse(invitation, T);
        InvitationResponseDTO late = mapper.toResponse(invitation, T.plus(CommunityInvitation.VALIDITY));

        assertThat(live.expired()).isFalse();
        assertThat(live.token()).isEqualTo("t");
        assertThat(live.inviterName()).isEqualTo("admin");
        assertThat(late.expired()).isTrue();
    }

    @Test
    void toResponse_shouldMapNothingToNull() {
        assertThat(mapper.toResponse((Community) null)).isNull();
        assertThat(mapper.toResponse((CommunityMember) null)).isNull();
        assertThat(mapper.toResponse((CommunityLeave) null)).isNull();
    }
}
