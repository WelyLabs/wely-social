package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Cypher of {@link Neo4jCommunityRepositoryAdapter} on a real Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jCommunityRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String ALICE = "alice-id";
    private static final String BOB = "bob-id";
    private static final String CAROL = "carol-id";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private Neo4jCommunityRepositoryAdapter adapter;

    @Autowired
    private Neo4jRelationshipRepositoryAdapter relationships;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @Autowired
    private Neo4jDeletedUserMarkersAdapter markers;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        for (String[] user : new String[][]{{ALICE, "alice", "1111"}, {BOB, "bob", "2222"}, {CAROL, "carol", "3333"}}) {
            relationships.save(new UserCreatedEventDTO(user[0], user[1], Integer.parseInt(user[2]), null)).block();
        }
    }

    private Community create(String creator, String name, CommunityVisibility visibility, String... interests) {
        return adapter.create(creator, new CommunityDraft(name, "about " + name, List.of(interests), visibility), T0).block();
    }

    private void befriend(String a, String bName, int bTag, String b) {
        relationships.sendFriendRequest(a, bName, bTag, null).block();
        relationships.acceptFriendRequest(b, a).block();
    }

    @Test
    @DisplayName("the creator is the first admin, and sees the community with their role")
    void create_shouldMakeTheCreatorAdmin() {
        Community created = create(ALICE, "Club", CommunityVisibility.PUBLIC, "auto", "balades");

        assertThat(created.communityId()).isNotBlank();
        assertThat(created.role()).isEqualTo(CommunityRole.ADMIN);
        assertThat(created.memberCount()).isEqualTo(1);
        assertThat(created.interests()).containsExactly("auto", "balades");
        assertThat(created.createdAt()).isEqualTo(T0);
        assertThat(created.description()).isEqualTo("about Club");
    }

    @Test
    @DisplayName("a creator without a node is refused rather than leaving a community nobody manages")
    void create_shouldRefuseAnUnknownCreator() {
        StepVerifier.create(adapter.create("ghost", new CommunityDraft("Club", null, List.of(), CommunityVisibility.PUBLIC), T0))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.USER_DOES_NOT_EXIST)
                .verify();
    }

    @Test
    @DisplayName("each viewer sees their own role and their own friends among the members")
    void findById_shouldBeRelativeToTheViewer() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0.plusSeconds(60)).block();
        befriend(CAROL, "bob", 2222, BOB);

        Community seenByCarol = adapter.findById(club.communityId(), CAROL).block();
        Community seenByBob = adapter.findById(club.communityId(), BOB).block();

        assertThat(seenByCarol.role()).isNull();
        assertThat(seenByCarol.memberCount()).isEqualTo(2);
        assertThat(seenByCarol.friendMemberCount()).isEqualTo(1);
        assertThat(seenByCarol.friendMembers()).singleElement().satisfies(friend -> {
            assertThat(friend.userId()).isEqualTo(BOB);
            assertThat(friend.userName()).isEqualTo("bob");
            assertThat(friend.hashtag()).isEqualTo(2222);
        });
        assertThat(seenByBob.role()).isEqualTo(CommunityRole.MEMBER);
        assertThat(seenByBob.friendMembers()).isEmpty();
    }

    @Test
    @DisplayName("an unknown community reads as empty")
    void findById_shouldBeEmptyForAnUnknownCommunity() {
        StepVerifier.create(adapter.findById("nope", ALICE)).verifyComplete();
    }

    @Test
    @DisplayName("an update replaces the fields and answers the community as the viewer sees it")
    void update_shouldReplaceTheFields() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC, "auto");

        Community updated = adapter.update(club.communityId(), ALICE,
                new CommunityDraft("Club privé", null, List.of("rando"), CommunityVisibility.PRIVATE)).block();

        assertThat(updated.name()).isEqualTo("Club privé");
        assertThat(updated.description()).isNull();
        assertThat(updated.interests()).containsExactly("rando");
        assertThat(updated.visibility()).isEqualTo(CommunityVisibility.PRIVATE);
        assertThat(updated.role()).isEqualTo(CommunityRole.ADMIN);
    }

    @Test
    @DisplayName("discovery lists public communities only, most members first, filtered by text and interest")
    void findPublic_shouldFilterAndSort() {
        Community small = create(ALICE, "Petit club auto", CommunityVisibility.PUBLIC, "auto");
        Community big = create(BOB, "Randonneurs", CommunityVisibility.PUBLIC, "rando");
        adapter.addMember(big.communityId(), CAROL, CommunityRole.MEMBER, T0).block();
        create(CAROL, "Secret auto", CommunityVisibility.PRIVATE, "auto");

        assertThat(adapter.findPublic(ALICE, null, null, 10).map(Community::name).collectList().block())
                .containsExactly("Randonneurs", "Petit club auto");
        assertThat(adapter.findPublic(ALICE, "AUTO", null, 10).map(Community::communityId).collectList().block())
                .containsExactly(small.communityId());
        assertThat(adapter.findPublic(ALICE, "about rando", null, 10).map(Community::communityId).collectList().block())
                .containsExactly(big.communityId());
        assertThat(adapter.findPublic(ALICE, null, "rando", 10).map(Community::communityId).collectList().block())
                .containsExactly(big.communityId());
        assertThat(adapter.findPublic(ALICE, null, null, 1).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("my communities include the private ones, by name")
    void findJoined_shouldListPublicAndPrivate() {
        create(ALICE, "Zèbres", CommunityVisibility.PRIVATE);
        create(ALICE, "Autos", CommunityVisibility.PUBLIC);
        create(BOB, "Pas à moi", CommunityVisibility.PUBLIC);

        assertThat(adapter.findJoined(ALICE).map(Community::name).collectList().block()).containsExactly("Autos", "Zèbres");
    }

    @Test
    @DisplayName("joining twice keeps one membership, and never demotes an admin")
    void addMember_shouldBeIdempotent() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0).block();
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0).block();
        adapter.addMember(club.communityId(), ALICE, CommunityRole.MEMBER, T0).block();

        assertThat(adapter.findById(club.communityId(), ALICE).block().memberCount()).isEqualTo(2);
        assertThat(adapter.roleOf(club.communityId(), ALICE).block()).isEqualTo(CommunityRole.ADMIN);
    }

    @Test
    @DisplayName("roleOf is empty for a non-member")
    void roleOf_shouldBeEmptyForANonMember() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);

        StepVerifier.create(adapter.roleOf(club.communityId(), BOB)).verifyComplete();
    }

    @Test
    @DisplayName("removing and changing a role say whether they matched")
    void removeMemberAndSetRole_shouldReportWhetherTheyMatched() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0).block();

        assertThat(adapter.setRole(club.communityId(), BOB, CommunityRole.ADMIN).block()).isTrue();
        assertThat(adapter.countAdmins(club.communityId(), Set.of()).block()).isEqualTo(2);
        assertThat(adapter.setRole(club.communityId(), CAROL, CommunityRole.ADMIN).block()).isFalse();
        assertThat(adapter.removeMember(club.communityId(), BOB).block()).isTrue();
        assertThat(adapter.removeMember(club.communityId(), BOB).block()).isFalse();
        assertThat(adapter.countAdmins("nope", Set.of()).block()).isZero();
    }

    @Test
    @DisplayName("members come admins first, then by when they joined; the longest-standing excludes the leaver")
    void findMembers_shouldOrderAdminsFirst() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), CAROL, CommunityRole.MEMBER, T0.plus(2, ChronoUnit.DAYS)).block();
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0.plus(1, ChronoUnit.DAYS)).block();

        List<CommunityMember> members = adapter.findMembers(club.communityId()).collectList().block();

        assertThat(members).extracting(CommunityMember::userId).containsExactly(ALICE, BOB, CAROL);
        assertThat(members.get(0).role()).isEqualTo(CommunityRole.ADMIN);
        assertThat(members.get(1).joinedAt()).isEqualTo(T0.plus(1, ChronoUnit.DAYS));
        assertThat(adapter.findLongestStandingMember(club.communityId(), Set.of(ALICE)).block()).isEqualTo(BOB);
        StepVerifier.create(adapter.findLongestStandingMember("nope", Set.of(ALICE))).verifyComplete();
    }

    @Test
    @DisplayName("an invitation reads back with both names, and a new one replaces it")
    void saveInvitation_shouldReplaceThePreviousOne() {
        Community club = create(ALICE, "Club", CommunityVisibility.PRIVATE);
        Instant sent = T0.plusSeconds(3600);

        CommunityInvitation first = adapter.saveInvitation(club.communityId(), ALICE, BOB, "token-one-aaaaaaaaaa",
                T0, T0.plus(CommunityInvitation.VALIDITY)).block();
        CommunityInvitation second = adapter.saveInvitation(club.communityId(), ALICE, BOB, "token-two-bbbbbbbbbb",
                sent, sent.plus(CommunityInvitation.VALIDITY)).block();

        assertThat(first.inviterName()).isEqualTo("alice");
        assertThat(first.inviteeName()).isEqualTo("bob");
        assertThat(first.communityName()).isEqualTo("Club");
        assertThat(first.visibility()).isEqualTo(CommunityVisibility.PRIVATE);
        assertThat(second.expiresAt()).isEqualTo(sent.plus(CommunityInvitation.VALIDITY));
        StepVerifier.create(adapter.findInvitation("token-one-aaaaaaaaaa")).verifyComplete();
        assertThat(adapter.findInvitations(club.communityId()).map(CommunityInvitation::token).collectList().block())
                .containsExactly("token-two-bbbbbbbbbb");
    }

    @Test
    @DisplayName("invitations list newest first; deleting one says whether it existed in that community")
    void findAndDeleteInvitations() {
        Community club = create(ALICE, "Club", CommunityVisibility.PRIVATE);
        adapter.saveInvitation(club.communityId(), ALICE, BOB, "token-bob-aaaaaaaaaa", T0, T0.plusSeconds(10)).block();
        adapter.saveInvitation(club.communityId(), ALICE, CAROL, "token-carol-aaaaaaaa", T0.plusSeconds(5), T0.plusSeconds(15)).block();

        assertThat(adapter.findInvitations(club.communityId()).map(CommunityInvitation::inviteeId).collectList().block())
                .containsExactly(CAROL, BOB);
        assertThat(adapter.deleteInvitation("other-community", "token-bob-aaaaaaaaaa").block()).isFalse();
        assertThat(adapter.deleteInvitation(club.communityId(), "token-bob-aaaaaaaaaa").block()).isTrue();
        assertThat(adapter.deleteInvitation(club.communityId(), "token-bob-aaaaaaaaaa").block()).isFalse();
    }

    @Test
    @DisplayName("deleting a community takes its memberships and invitations with it, not its members")
    void delete_shouldDetachEverything() {
        Community club = create(ALICE, "Club", CommunityVisibility.PRIVATE);
        adapter.saveInvitation(club.communityId(), ALICE, BOB, "token-bob-aaaaaaaaaa", T0, T0.plusSeconds(10)).block();

        adapter.delete(club.communityId()).block();

        StepVerifier.create(adapter.findById(club.communityId(), ALICE)).verifyComplete();
        StepVerifier.create(adapter.findInvitation("token-bob-aaaaaaaaaa")).verifyComplete();
        assertThat(adapter.findJoined(ALICE).collectList().block()).isEmpty();
        Long users = neo4jClient.query("MATCH (u:User) RETURN count(u) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
        assertThat(users).isEqualTo(3);
    }

    @Test
    @DisplayName("only eligible admins count: excluded ids and admins with a deletion marker are skipped")
    void countAdmins_shouldSkipExcludedAdminsAndDeletedOnes() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.ADMIN, T0.plus(1, ChronoUnit.DAYS)).block();
        adapter.addMember(club.communityId(), CAROL, CommunityRole.ADMIN, T0.plus(2, ChronoUnit.DAYS)).block();

        assertThat(adapter.countAdmins(club.communityId(), Set.of(ALICE)).block()).isEqualTo(2);
        assertThat(adapter.countAdmins(club.communityId(), Set.of(ALICE, BOB)).block()).isEqualTo(1);
        markers.markDeleted(CAROL, T0.plus(3, ChronoUnit.DAYS)).block();
        assertThat(adapter.countAdmins(club.communityId(), Set.of(ALICE, BOB)).block()).isZero();
        assertThat(adapter.countAdmins(club.communityId(), Set.of()).block()).isEqualTo(2);
    }

    @Test
    @DisplayName("a member whose account is deleted, erasure pending, is never chosen to take over")
    void findLongestStandingMember_shouldSkipUsers_whenTheyHaveADeletionMarker() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0.plus(1, ChronoUnit.DAYS)).block();
        adapter.addMember(club.communityId(), CAROL, CommunityRole.MEMBER, T0.plus(2, ChronoUnit.DAYS)).block();
        markers.markDeleted(BOB, T0.plus(3, ChronoUnit.DAYS)).block();

        assertThat(adapter.findLongestStandingMember(club.communityId(), Set.of(ALICE)).block()).isEqualTo(CAROL);
    }

    @Test
    @DisplayName("every excluded member is skipped: the leaver and the protected demo accounts")
    void findLongestStandingMember_shouldSkipEveryExcludedUser() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0.plus(1, ChronoUnit.DAYS)).block();

        assertThat(adapter.findLongestStandingMember(club.communityId(), Set.of(ALICE)).block()).isEqualTo(BOB);
        StepVerifier.create(adapter.findLongestStandingMember(club.communityId(), Set.of(ALICE, BOB))).verifyComplete();
    }

    @Test
    @DisplayName("two members who joined at the same instant: the smallest userId, so every erasure agrees")
    void findLongestStandingMember_shouldPickTheSmallestUserId_whenTwoJoinedAtTheSameTime() {
        Community club = create(ALICE, "Club", CommunityVisibility.PUBLIC);
        adapter.addMember(club.communityId(), CAROL, CommunityRole.MEMBER, T0.plus(1, ChronoUnit.DAYS)).block();
        adapter.addMember(club.communityId(), BOB, CommunityRole.MEMBER, T0.plus(1, ChronoUnit.DAYS)).block();

        assertThat(adapter.findLongestStandingMember(club.communityId(), Set.of(ALICE)).block()).isEqualTo(BOB);
    }

    @Test
    void deleteInvitationsSentBy_shouldDeleteOnlyThatInvitersInvitations() {
        Community club = create(ALICE, "Club", CommunityVisibility.PRIVATE);
        Community other = create(BOB, "Other", CommunityVisibility.PRIVATE);
        adapter.saveInvitation(club.communityId(), ALICE, BOB, "token-alice-1aaaaaaaa", T0, T0.plusSeconds(60)).block();
        adapter.saveInvitation(other.communityId(), ALICE, CAROL, "token-alice-2aaaaaaaa", T0, T0.plusSeconds(60)).block();
        adapter.saveInvitation(other.communityId(), BOB, ALICE, "token-bob-aaaaaaaaaaa", T0, T0.plusSeconds(60)).block();

        StepVerifier.create(adapter.deleteInvitationsSentBy(ALICE)).expectNext(2L).verifyComplete();

        StepVerifier.create(adapter.findInvitation("token-alice-1aaaaaaaa")).verifyComplete();
        StepVerifier.create(adapter.findInvitation("token-alice-2aaaaaaaa")).verifyComplete();
        assertThat(adapter.findInvitation("token-bob-aaaaaaaaaaa").block().inviteeId()).isEqualTo(ALICE);
    }

    @Test
    void deleteInvitationsSentBy_shouldReturnZero_whenThereAreNone() {
        StepVerifier.create(adapter.deleteInvitationsSentBy(ALICE)).expectNext(0L).verifyComplete();
    }

    @Test
    @DisplayName("only the user's own communities left with no member at all are deleted")
    void deleteEmptyCommunitiesCreatedBy_shouldDeleteOnlyEmptyOnes_whenSomeStillHaveMembers() {
        Community empty = create(ALICE, "Empty", CommunityVisibility.PUBLIC);
        adapter.removeMember(empty.communityId(), ALICE).block();
        Community stillUsed = create(ALICE, "Still used", CommunityVisibility.PUBLIC);
        adapter.addMember(stillUsed.communityId(), BOB, CommunityRole.MEMBER, T0).block();
        adapter.removeMember(stillUsed.communityId(), ALICE).block();
        Community someoneElses = create(BOB, "Someone else's", CommunityVisibility.PUBLIC);
        adapter.removeMember(someoneElses.communityId(), BOB).block();

        StepVerifier.create(adapter.deleteEmptyCommunitiesCreatedBy(ALICE)).expectNext(1L).verifyComplete();

        StepVerifier.create(adapter.findById(empty.communityId(), ALICE)).verifyComplete();
        assertThat(adapter.findById(stillUsed.communityId(), BOB).block()).isNotNull();
        assertThat(adapter.findById(someoneElses.communityId(), BOB).block()).isNotNull();
    }

    @Test
    void deleteEmptyCommunitiesCreatedBy_shouldReturnZero_whenNoneIsEmpty() {
        create(ALICE, "Club", CommunityVisibility.PUBLIC);

        StepVerifier.create(adapter.deleteEmptyCommunitiesCreatedBy(ALICE)).expectNext(0L).verifyComplete();
    }
}
