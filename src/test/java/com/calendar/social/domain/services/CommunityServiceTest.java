package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String ME = "me";
    private static final String ID = "c1";
    private static final String TOKEN = "token-abcdefghijklmnop";
    private static final String DEMO = "demo";

    @Mock
    private CommunityRepository repository;

    @Mock
    private RelationshipRepository relationships;

    private CommunityService service;

    @BeforeEach
    void setUp() {
        service = new CommunityService(repository, relationships, Clock.fixed(NOW, ZoneOffset.UTC), () -> TOKEN,
                Set.of(DEMO));
    }

    private static Community community(CommunityVisibility visibility, CommunityRole role, long members) {
        return new Community(ID, "Club", null, List.of(), visibility, NOW, members, role, List.of(), 0);
    }

    private static CommunityDraft draft() {
        return new CommunityDraft("Club auto", "Voitures", List.of("auto"), CommunityVisibility.PUBLIC);
    }

    private static CommunityInvitation invitation(String inviteeId, Instant expiresAt) {
        return new CommunityInvitation(TOKEN, ID, "Club", CommunityVisibility.PRIVATE, "admin", "admin",
                inviteeId, "invitee", expiresAt.minus(CommunityInvitation.VALIDITY), expiresAt);
    }

    private static Friendship friendship() {
        return new Friendship("friend", Closeness.FRIEND, null, false, 0L, NOW);
    }

    private void given(Community community) {
        when(repository.findById(ID, ME)).thenReturn(Mono.just(community));
    }

    private static boolean failsWith(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException business && business.getErrorCode() == code;
    }

    // --- create / update / delete ---------------------------------------------------------------

    @Test
    void create_shouldStoreAValidDraftAtTheClocksTime() {
        Community created = community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 1);
        when(repository.create(ME, draft(), NOW)).thenReturn(Mono.just(created));

        StepVerifier.create(service.create(ME, draft())).expectNext(created).verifyComplete();
    }

    @Test
    void create_shouldRefuseAnInvalidDraft() {
        CommunityDraft invalid = new CommunityDraft("ab", null, List.of(), CommunityVisibility.PUBLIC);

        StepVerifier.create(service.create(ME, invalid))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_INVALID))
                .verify();
        verify(repository, never()).create(anyString(), any(), any());
    }

    @Test
    void create_shouldRefuseNoDraftAtAll() {
        StepVerifier.create(service.create(ME, null))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_INVALID))
                .verify();
    }

    @Test
    void update_shouldReplaceTheCommunity_forAnAdmin() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 2));
        Community updated = community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 2);
        when(repository.update(ID, ME, draft())).thenReturn(Mono.just(updated));

        StepVerifier.create(service.update(ME, ID, draft())).expectNext(updated).verifyComplete();
    }

    @Test
    void update_shouldAnswerNotFound_whenTheCommunityWentMeanwhile() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 2));
        when(repository.update(ID, ME, draft())).thenReturn(Mono.empty());

        StepVerifier.create(service.update(ME, ID, draft()))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_NOT_FOUND))
                .verify();
    }

    @Test
    void update_shouldRefuseAMember() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.MEMBER, 2));

        StepVerifier.create(service.update(ME, ID, draft()))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_ADMIN_REQUIRED))
                .verify();
    }

    @Test
    void delete_shouldDelete_forAnAdmin() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        when(repository.delete(ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(ME, ID)).verifyComplete();
        verify(repository).delete(ID);
    }

    @Test
    void delete_shouldRefuseANonAdmin() {
        given(community(CommunityVisibility.PUBLIC, null, 3));

        StepVerifier.create(service.delete(ME, ID))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_ADMIN_REQUIRED))
                .verify();
    }

    // --- find / discover / mine -----------------------------------------------------------------

    @Test
    void find_shouldShowAPublicCommunityToANonMember() {
        Community open = community(CommunityVisibility.PUBLIC, null, 4);
        given(open);

        StepVerifier.create(service.find(ME, ID)).expectNext(open).verifyComplete();
    }

    @Test
    void find_shouldHideAPrivateCommunityFromANonMember_asIfItDidNotExist() {
        given(community(CommunityVisibility.PRIVATE, null, 4));

        StepVerifier.create(service.find(ME, ID))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_NOT_FOUND))
                .verify();
    }

    @Test
    void find_shouldShowAPrivateCommunityToAMember() {
        Community closed = community(CommunityVisibility.PRIVATE, CommunityRole.MEMBER, 4);
        given(closed);

        StepVerifier.create(service.find(ME, ID)).expectNext(closed).verifyComplete();
    }

    @Test
    void find_shouldAnswerNotFound_forAnUnknownCommunity() {
        when(repository.findById(ID, ME)).thenReturn(Mono.empty());

        StepVerifier.create(service.find(ME, ID))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_NOT_FOUND))
                .verify();
    }

    @Test
    void discover_shouldTrimTheQueryAndLowerCaseTheInterest() {
        Community open = community(CommunityVisibility.PUBLIC, null, 1);
        when(repository.findPublic(ME, "auto", "voitures", CommunityService.DISCOVERY_LIMIT)).thenReturn(Flux.just(open));

        StepVerifier.create(service.discover(ME, "  auto ", " Voitures ")).expectNext(open).verifyComplete();
    }

    @Test
    void discover_shouldTreatBlankFiltersAsNone() {
        when(repository.findPublic(ME, null, null, CommunityService.DISCOVERY_LIMIT)).thenReturn(Flux.empty());

        StepVerifier.create(service.discover(ME, " ", null)).verifyComplete();
    }

    @Test
    void mine_shouldListTheJoinedCommunities() {
        Community joined = community(CommunityVisibility.PRIVATE, CommunityRole.MEMBER, 2);
        when(repository.findJoined(ME)).thenReturn(Flux.just(joined));

        StepVerifier.create(service.mine(ME)).expectNext(joined).verifyComplete();
    }

    // --- join -----------------------------------------------------------------------------------

    @Test
    void join_shouldAddTheUserToAPublicCommunity_atOnce() {
        Community joined = community(CommunityVisibility.PUBLIC, CommunityRole.MEMBER, 5);
        when(repository.findById(ID, ME))
                .thenReturn(Mono.just(community(CommunityVisibility.PUBLIC, null, 4)))
                .thenReturn(Mono.just(joined));
        when(repository.addMember(ID, ME, CommunityRole.MEMBER, NOW)).thenReturn(Mono.empty());

        StepVerifier.create(service.join(ME, ID)).expectNext(joined).verifyComplete();
    }

    @Test
    void join_shouldChangeNothing_forAMember() {
        Community member = community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 4);
        given(member);

        StepVerifier.create(service.join(ME, ID)).expectNext(member).verifyComplete();
        verify(repository, never()).addMember(anyString(), anyString(), any(), any());
    }

    @Test
    void join_shouldRefuseAPrivateCommunity_asNotFound() {
        given(community(CommunityVisibility.PRIVATE, null, 4));

        StepVerifier.create(service.join(ME, ID))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_NOT_FOUND))
                .verify();
    }

    // --- leave ----------------------------------------------------------------------------------

    @Test
    void leave_shouldChangeNothing_forANonMember() {
        given(community(CommunityVisibility.PUBLIC, null, 4));

        StepVerifier.create(service.leave(ME, ID, false)).expectNext(CommunityLeave.left()).verifyComplete();
        verify(repository, never()).removeMember(anyString(), anyString());
    }

    @Test
    void leave_shouldRemoveAMember() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.MEMBER, 4));
        when(repository.removeMember(ID, ME)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leave(ME, ID, false)).expectNext(CommunityLeave.left()).verifyComplete();
    }

    @Test
    void leave_shouldRemoveAnAdmin_whenOtherEligibleAdminsRemain() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 4));
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(1L));
        when(repository.removeMember(ID, ME)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leave(ME, ID, false)).expectNext(CommunityLeave.left()).verifyComplete();
        verify(repository, never()).setRole(anyString(), anyString(), any());
    }

    @Test
    void leave_shouldHandOverToTheLongestStandingMember_whenTheLastAdminLeaves() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 4));
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.just("oldest"));
        when(repository.setRole(ID, "oldest", CommunityRole.ADMIN)).thenReturn(Mono.just(true));
        when(repository.removeMember(ID, ME)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leave(ME, ID, false))
                .expectNext(CommunityLeave.handedOverTo("oldest"))
                .verifyComplete();
    }

    @Test
    void leave_shouldAnswerLastMember_whenEveryoneElseLeftMeanwhile() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 2));
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.empty());

        StepVerifier.create(service.leave(ME, ID, false))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_LAST_MEMBER))
                .verify();
    }

    @Test
    void leave_shouldDeleteTheCommunity_whenNoMemberCanTakeOverAndTheUserConfirms() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 2));
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.empty());
        when(repository.delete(ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.leave(ME, ID, true)).expectNext(CommunityLeave.deleted()).verifyComplete();
        verify(repository, never()).removeMember(anyString(), anyString());
    }

    @Test
    void leave_shouldNeverHandOverToAProtectedAccount_whenTheLastAdminLeaves() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.just("real"));
        when(repository.setRole(ID, "real", CommunityRole.ADMIN)).thenReturn(Mono.just(true));
        when(repository.removeMember(ID, ME)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leave(ME, ID, false)).expectNext(CommunityLeave.handedOverTo("real")).verifyComplete();
        verify(repository, never()).setRole(ID, DEMO, CommunityRole.ADMIN);
    }

    @Test
    @DisplayName("a protected admin does not count as one who remains: a real member takes over")
    void leave_shouldHandOverToARealMember_whenTheOnlyOtherAdminIsProtected() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        // The repository skips the excluded ids, so the demo admin is not counted.
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.just("real"));
        when(repository.setRole(ID, "real", CommunityRole.ADMIN)).thenReturn(Mono.just(true));
        when(repository.removeMember(ID, ME)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leave(ME, ID, false)).expectNext(CommunityLeave.handedOverTo("real")).verifyComplete();
        verify(repository).setRole(ID, "real", CommunityRole.ADMIN);
    }

    @Test
    void leave_shouldPropagateTheError_whenCountingAdminsFails() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.error(failure));

        StepVerifier.create(service.leave(ME, ID, false)).expectErrorMatches(error -> error == failure).verify();
        verify(repository, never()).removeMember(anyString(), anyString());
    }

    @Test
    void leave_shouldAskForConfirmation_whenTheOnlyMemberLeaves() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 1));

        StepVerifier.create(service.leave(ME, ID, false))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_LAST_MEMBER))
                .verify();
        verify(repository, never()).delete(anyString());
    }

    @Test
    void leave_shouldDeleteTheCommunity_whenTheOnlyMemberConfirms() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 1));
        when(repository.delete(ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.leave(ME, ID, true)).expectNext(CommunityLeave.deleted()).verifyComplete();
    }

    // --- leaveOnErasure -------------------------------------------------------------------------

    private static Community membership(CommunityRole role, long members) {
        return community(CommunityVisibility.PUBLIC, role, members);
    }

    @Test
    void leaveOnErasure_shouldDeleteTheCommunity_whenTheUserIsItsOnlyMember() {
        when(repository.delete(ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 1)))
                .expectNext(CommunityLeave.deleted())
                .verifyComplete();
    }

    @Test
    @DisplayName("a plain member's membership goes with the node: nothing to write")
    void leaveOnErasure_shouldWriteNothing_whenTheUserIsAPlainMember() {
        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.MEMBER, 4)))
                .expectNext(CommunityLeave.left())
                .verifyComplete();
        verifyNoInteractions(repository);
    }

    @Test
    void leaveOnErasure_shouldWriteNothing_whenOtherEligibleAdminsRemain() {
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(1L));

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 4)))
                .expectNext(CommunityLeave.left())
                .verifyComplete();
        verify(repository, never()).setRole(anyString(), anyString(), any());
        verify(repository, never()).delete(anyString());
    }

    @Test
    void leaveOnErasure_shouldPromoteTheLongestStandingMember_whenTheUserIsTheLastAdmin() {
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.just("oldest"));
        when(repository.setRole(ID, "oldest", CommunityRole.ADMIN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 4)))
                .expectNext(CommunityLeave.handedOverTo("oldest"))
                .verifyComplete();
        // Never asks the erased user to confirm anything, and leaves the membership to the node's deletion.
        verify(repository, never()).removeMember(anyString(), anyString());
        verify(repository, never()).delete(anyString());
    }

    @Test
    @DisplayName("the demo accounts are skipped: with only them left, the community is deleted")
    void leaveOnErasure_shouldDeleteTheCommunity_whenNoOtherMemberCanTakeOver() {
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.empty());
        when(repository.delete(ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 2)))
                .expectNext(CommunityLeave.deleted())
                .verifyComplete();
        verify(repository, never()).setRole(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a protected admin does not count as one who remains: the demo account never keeps it alone")
    void leaveOnErasure_shouldPromoteARealMember_whenTheOnlyOtherAdminIsProtected() {
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.just(0L));
        when(repository.findLongestStandingMember(ID, Set.of(ME, DEMO))).thenReturn(Mono.just("real"));
        when(repository.setRole(ID, "real", CommunityRole.ADMIN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 3)))
                .expectNext(CommunityLeave.handedOverTo("real"))
                .verifyComplete();
        verify(repository, never()).setRole(ID, DEMO, CommunityRole.ADMIN);
    }

    @Test
    void leaveOnErasure_shouldPropagateTheError_whenTheGraphFails() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(repository.countAdmins(ID, Set.of(ME, DEMO))).thenReturn(Mono.error(failure));

        StepVerifier.create(service.leaveOnErasure(ME, membership(CommunityRole.ADMIN, 3)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    // --- members --------------------------------------------------------------------------------

    @Test
    void members_shouldListThem_forAMember() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.MEMBER, 2));
        CommunityMember member = new CommunityMember("u", "u", 1, null, CommunityRole.ADMIN, NOW);
        when(repository.findMembers(ID)).thenReturn(Flux.just(member));

        StepVerifier.create(service.members(ME, ID)).expectNext(member).verifyComplete();
    }

    @Test
    void members_shouldBeMembersOnly_inAPublicCommunity() {
        given(community(CommunityVisibility.PUBLIC, null, 2));

        StepVerifier.create(service.members(ME, ID))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEMBERS_ONLY))
                .verify();
    }

    // --- changeRole -----------------------------------------------------------------------------

    @Test
    void changeRole_shouldPromoteAMember() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.roleOf(ID, "m")).thenReturn(Mono.just(CommunityRole.MEMBER));
        when(repository.setRole(ID, "m", CommunityRole.ADMIN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.changeRole(ME, ID, "m", CommunityRole.ADMIN)).verifyComplete();
        verify(repository).setRole(ID, "m", CommunityRole.ADMIN);
    }

    @Test
    void changeRole_shouldDoNothing_whenTheRoleIsAlreadyThatOne() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.roleOf(ID, "m")).thenReturn(Mono.just(CommunityRole.ADMIN));

        StepVerifier.create(service.changeRole(ME, ID, "m", CommunityRole.ADMIN)).verifyComplete();
        verify(repository, never()).setRole(anyString(), anyString(), any());
    }

    @Test
    void changeRole_shouldDemoteAnAdmin_whenAnotherRemains() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.roleOf(ID, ME)).thenReturn(Mono.just(CommunityRole.ADMIN));
        when(repository.countAdmins(ID, Set.of())).thenReturn(Mono.just(2L));
        when(repository.setRole(ID, ME, CommunityRole.MEMBER)).thenReturn(Mono.just(true));

        StepVerifier.create(service.changeRole(ME, ID, ME, CommunityRole.MEMBER)).verifyComplete();
    }

    @Test
    void changeRole_shouldRefuseToDemoteTheLastAdmin() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.roleOf(ID, ME)).thenReturn(Mono.just(CommunityRole.ADMIN));
        when(repository.countAdmins(ID, Set.of())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.changeRole(ME, ID, ME, CommunityRole.MEMBER))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.LAST_ADMIN))
                .verify();
        verify(repository, never()).setRole(anyString(), anyString(), any());
    }

    @Test
    void changeRole_shouldAnswerMemberNotFound_forANonMember() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.roleOf(ID, "stranger")).thenReturn(Mono.empty());

        StepVerifier.create(service.changeRole(ME, ID, "stranger", CommunityRole.ADMIN))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEMBER_NOT_FOUND))
                .verify();
    }

    // --- removeMember ---------------------------------------------------------------------------

    @Test
    void removeMember_shouldRemoveAnother_forAnAdmin() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.removeMember(ID, "m")).thenReturn(Mono.just(true));

        StepVerifier.create(service.removeMember(ME, ID, "m")).verifyComplete();
    }

    @Test
    void removeMember_shouldAnswerMemberNotFound_whenTheyWereNotOne() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.ADMIN, 3));
        when(repository.removeMember(ID, "m")).thenReturn(Mono.just(false));

        StepVerifier.create(service.removeMember(ME, ID, "m"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEMBER_NOT_FOUND))
                .verify();
    }

    @Test
    void removeMember_shouldRefuseRemovingOneself_whichIsLeaving() {
        StepVerifier.create(service.removeMember(ME, ID, ME))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEMBER_NOT_FOUND))
                .verify();
    }

    // --- invitations ----------------------------------------------------------------------------

    @Test
    void invite_shouldSendAThreeDayInvitationToAFriend() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        CommunityInvitation sent = invitation("friend", NOW.plus(CommunityInvitation.VALIDITY));
        when(relationships.findFriendship(ME, "friend")).thenReturn(Mono.just(friendship()));
        when(repository.roleOf(ID, "friend")).thenReturn(Mono.empty());
        when(repository.saveInvitation(ID, ME, "friend", TOKEN, NOW, NOW.plus(CommunityInvitation.VALIDITY)))
                .thenReturn(Mono.just(sent));

        StepVerifier.create(service.invite(ME, ID, "friend")).expectNext(sent).verifyComplete();
    }

    @Test
    void invite_shouldRefuseSomeoneWhoIsNotAFriend() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        when(relationships.findFriendship(ME, "stranger")).thenReturn(Mono.empty());

        StepVerifier.create(service.invite(ME, ID, "stranger"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITEE_INVALID))
                .verify();
    }

    @Test
    void invite_shouldRefuseAFriendWhoIsAMemberAlready() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        when(relationships.findFriendship(ME, "friend")).thenReturn(Mono.just(friendship()));
        when(repository.roleOf(ID, "friend")).thenReturn(Mono.just(CommunityRole.MEMBER));

        StepVerifier.create(service.invite(ME, ID, "friend"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITEE_INVALID))
                .verify();
        verify(repository, never()).saveInvitation(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void invite_shouldRefuseOneself() {
        StepVerifier.create(service.invite(ME, ID, ME))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITEE_INVALID))
                .verify();
    }

    @Test
    void invite_shouldRefuseNobody() {
        StepVerifier.create(service.invite(ME, ID, " "))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITEE_INVALID))
                .verify();
    }

    @Test
    void invite_shouldBeForAdminsOnly() {
        given(community(CommunityVisibility.PUBLIC, CommunityRole.MEMBER, 3));

        StepVerifier.create(service.invite(ME, ID, "friend"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_ADMIN_REQUIRED))
                .verify();
    }

    @Test
    void invitations_shouldListThem_forAnAdmin() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        CommunityInvitation pending = invitation("friend", NOW.plusSeconds(60));
        when(repository.findInvitations(ID)).thenReturn(Flux.just(pending));

        StepVerifier.create(service.invitations(ME, ID)).expectNext(pending).verifyComplete();
    }

    @Test
    void revoke_shouldDeleteTheInvitation() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        when(repository.deleteInvitation(ID, TOKEN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.revoke(ME, ID, TOKEN)).verifyComplete();
    }

    @Test
    void revoke_shouldAnswerNotFound_forAnUnknownToken() {
        given(community(CommunityVisibility.PRIVATE, CommunityRole.ADMIN, 3));
        when(repository.deleteInvitation(ID, TOKEN)).thenReturn(Mono.just(false));

        StepVerifier.create(service.revoke(ME, ID, TOKEN))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITATION_NOT_FOUND))
                .verify();
    }

    @Test
    void invitation_shouldShowItToTheInvitee_evenExpired() {
        CommunityInvitation expired = invitation(ME, NOW.minusSeconds(1));
        when(repository.findInvitation(TOKEN)).thenReturn(Mono.just(expired));

        StepVerifier.create(service.invitation(ME, TOKEN)).expectNext(expired).verifyComplete();
    }

    @Test
    void invitation_shouldHideItFromAnyoneElse() {
        when(repository.findInvitation(TOKEN)).thenReturn(Mono.just(invitation("someone-else", NOW.plusSeconds(60))));

        StepVerifier.create(service.invitation(ME, TOKEN))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITATION_NOT_FOUND))
                .verify();
    }

    @Test
    void invitation_shouldAnswerNotFound_forAnUnknownToken() {
        when(repository.findInvitation(TOKEN)).thenReturn(Mono.empty());

        StepVerifier.create(service.invitation(ME, TOKEN))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITATION_NOT_FOUND))
                .verify();
    }

    @Test
    void accept_shouldJoinAndUseTheInvitationUp() {
        when(repository.findInvitation(TOKEN)).thenReturn(Mono.just(invitation(ME, NOW.plusSeconds(60))));
        when(repository.addMember(ID, ME, CommunityRole.MEMBER, NOW)).thenReturn(Mono.empty());
        when(repository.deleteInvitation(ID, TOKEN)).thenReturn(Mono.just(true));
        Community joined = community(CommunityVisibility.PRIVATE, CommunityRole.MEMBER, 4);
        given(joined);

        StepVerifier.create(service.accept(ME, TOKEN)).expectNext(joined).verifyComplete();
        verify(repository).deleteInvitation(ID, TOKEN);
    }

    @Test
    void accept_shouldRefuseAnExpiredInvitation() {
        when(repository.findInvitation(TOKEN)).thenReturn(Mono.just(invitation(ME, NOW)));

        StepVerifier.create(service.accept(ME, TOKEN))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.INVITATION_EXPIRED))
                .verify();
        verify(repository, never()).addMember(anyString(), anyString(), any(), any());
    }
}
