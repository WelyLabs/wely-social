package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.infrastructure.persistence.models.entities.DeletedUserNodeEntity;
import com.calendar.social.infrastructure.persistence.repositories.DeletedUserNodeRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Drives {@link AccountErasureService} end to end on a real Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest(properties = {
        "app.neo4j.schema-init.enabled=true",
        "app.account-deletion.protected-user-ids=" + AccountErasureServiceIntegrationTest.DEMO})
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class AccountErasureServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    static final String DEMO = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String ALICE = "alice-id";
    private static final String BOB = "bob-id";
    private static final String CAROL = "carol-id";
    private static final String DAVE = "dave-id";
    private static final String ERIN = "erin-id";
    private static final String FRANK = "frank-id";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:03:27.123Z");

    @Autowired
    private AccountErasureService service;

    @Autowired
    private RelationshipService relationshipService;

    @Autowired
    private CommunityService communityService;

    @Autowired
    private RelationshipRepository relationships;

    @Autowired
    private CommunityRepository communities;

    @Autowired
    private DeletedUserMarkers markers;

    @Autowired
    private DeletedUserNodeRepository markerNodes;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        // The constraint is created in the background at startup; the erasure must work with it in place.
        await().atMost(Duration.ofSeconds(30)).until(() -> count(
                "SHOW CONSTRAINTS YIELD name WHERE name = 'deleted_user_id' RETURN count(*) AS c") == 1);
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        given(ALICE, "alice", 1111);
        given(BOB, "bob", 2222);
        given(CAROL, "carol", 3333);
        given(DAVE, "dave", 4444);
        given(ERIN, "erin", 5555);
        given(FRANK, "frank", 6666);
        given(DEMO, "camille", 7777);
    }

    private void given(String userId, String userName, int hashtag) {
        relationships.save(new UserCreatedEventDTO(userId, userName, hashtag, null)).block();
    }

    private void erase(String userId) {
        service.erase(new UserDeletedEventDTO(userId, DELETED_AT)).block();
    }

    private long count(String cypher) {
        return count(cypher, Map.of());
    }

    private long count(String cypher, Map<String, Object> params) {
        return neo4jClient.query(cypher).bindAll(params)
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    private boolean nodeExists(String userId) {
        return count("MATCH (u:User {userId: $id}) RETURN count(u) AS c", Map.of("id", userId)) > 0;
    }

    private long edgesOf(String userId) {
        return count("MATCH (:User {userId: $id})-[r]-() RETURN count(r) AS c", Map.of("id", userId));
    }

    private Community club(String creator, String name) {
        return communities.create(creator, new CommunityDraft(name, null, List.of(), CommunityVisibility.PUBLIC), T0)
                .block();
    }

    private void join(Community community, String userId, int daysAfterCreation) {
        communities.addMember(community.communityId(), userId, CommunityRole.MEMBER,
                T0.plus(daysAfterCreation, ChronoUnit.DAYS)).block();
    }

    private CommunityRole roleOf(Community community, String userId) {
        return communities.roleOf(community.communityId(), userId).block();
    }

    private boolean exists(Community community) {
        return communities.findById(community.communityId(), BOB).blockOptional().isPresent();
    }

    private void friends(String sender, String target, String targetName, int targetHashtag) {
        relationships.sendFriendRequest(sender, targetName, targetHashtag, null).block();
        relationships.acceptFriendRequest(target, sender).block();
    }

    @Test
    void erase_shouldRemoveTheNodeAndEveryEdge_whenTheUserHasAcceptedPendingAndRejectedEdgesBothWays() {
        friends(ALICE, BOB, "bob", 2222);
        relationships.sendFriendRequest(ALICE, "carol", 3333, null).block();
        relationships.sendFriendRequest(DAVE, "alice", 1111, null).block();
        relationships.sendFriendRequest(ALICE, "erin", 5555, null).block();
        relationships.rejectFriendRequest(ERIN, ALICE).block();
        relationships.sendFriendRequest(FRANK, "alice", 1111, null).block();
        relationships.rejectFriendRequest(ALICE, FRANK).block();
        friends(BOB, CAROL, "carol", 3333);

        erase(ALICE);

        assertThat(nodeExists(ALICE)).isFalse();
        // Only the friendship between two other users is left.
        assertThat(count("MATCH ()-[r:RELATIONSHIP]->() RETURN count(r) AS c")).isEqualTo(1);
        assertThat(relationships.findAllFriends(BOB).collectList().block()).extracting("userId").containsExactly(CAROL);
        assertThat(relationships.findIncomingRequests(CAROL).collectList().block()).isEmpty();
        assertThat(relationships.findOutgoingRequests(DAVE).collectList().block()).isEmpty();
        assertThat(relationships.findAllWithSocialStatus(BOB).map(UserSocialDTO::userId).collectList().block())
                .doesNotContain(ALICE);
        DeletedUserNodeEntity marker = markerNodes.findById(ALICE).block();
        assertThat(marker.getDeletedAt().toInstant()).isEqualTo(DELETED_AT);
        assertThat(marker.getErasedAt()).isNotNull();
    }

    @Test
    @DisplayName("each former friendship leaves a nameless REMOVED record, committed with the erasure; pending ones none")
    void erase_shouldRecordARemovalPerFormerFriendship() {
        friends(ALICE, BOB, "bob", 2222);
        friends(CAROL, ALICE, "alice", 1111);
        relationships.sendFriendRequest(ALICE, "dave", 4444, null).block();

        erase(ALICE);

        assertThat(count("MATCH (e:FriendshipOutboxEvent:Unpublished {type: 'REMOVED', fromUserId: $id}) "
                + "WHERE e.fromUserName IS NULL AND e.closeness IS NULL AND e.previousCloseness = 'FRIEND' "
                + "AND e.toUserId IN [$bob, $carol] RETURN count(e) AS c",
                Map.of("id", ALICE, "bob", BOB, "carol", CAROL))).isEqualTo(2);
        assertThat(count("MATCH (e:FriendshipOutboxEvent) RETURN count(e) AS c")).isEqualTo(2);
    }

    @Test
    @DisplayName("the last admin's community goes to the longest-standing member, skipping one deleted meanwhile")
    void erase_shouldPromoteTheLongestStandingNonDeletedMember_whenTheUserWasTheLastAdmin() {
        Community club = club(ALICE, "Club");
        join(club, BOB, 1);
        join(club, CAROL, 2);
        join(club, DAVE, 3);
        markers.markDeleted(BOB, DELETED_AT).block();

        erase(ALICE);

        assertThat(roleOf(club, CAROL)).isEqualTo(CommunityRole.ADMIN);
        assertThat(roleOf(club, BOB)).isEqualTo(CommunityRole.MEMBER);
        assertThat(roleOf(club, DAVE)).isEqualTo(CommunityRole.MEMBER);
        assertThat(roleOf(club, ALICE)).isNull();
        assertThat(communities.findById(club.communityId(), CAROL).block().memberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a demo account is never handed a real community: the next member is, or the community goes")
    void erase_shouldNotPromoteTheDemoAccount() {
        Community shared = club(ALICE, "Shared");
        join(shared, DEMO, 1);
        join(shared, CAROL, 2);
        Community demoOnly = club(ALICE, "Demo only");
        join(demoOnly, DEMO, 1);

        erase(ALICE);

        assertThat(roleOf(shared, CAROL)).isEqualTo(CommunityRole.ADMIN);
        assertThat(roleOf(shared, DEMO)).isEqualTo(CommunityRole.MEMBER);
        assertThat(exists(demoOnly)).isFalse();
    }

    @Test
    @DisplayName("a demo admin does not keep a real community alone: a real member is promoted, or it goes")
    void erase_shouldPromoteARealMember_whenTheOnlyOtherAdminIsTheDemoAccount() {
        Community shared = club(ALICE, "Shared");
        join(shared, DEMO, 1);
        communities.setRole(shared.communityId(), DEMO, CommunityRole.ADMIN).block();
        join(shared, CAROL, 2);
        Community demoAdminOnly = club(ALICE, "Demo admin only");
        join(demoAdminOnly, DEMO, 1);
        communities.setRole(demoAdminOnly.communityId(), DEMO, CommunityRole.ADMIN).block();

        erase(ALICE);

        assertThat(roleOf(shared, CAROL)).isEqualTo(CommunityRole.ADMIN);
        assertThat(roleOf(shared, DEMO)).isEqualTo(CommunityRole.ADMIN);
        assertThat(exists(demoAdminOnly)).isFalse();
    }

    @Test
    void erase_shouldDeleteTheCommunity_whenTheUserWasItsOnlyMember() {
        Community club = club(ALICE, "Solo");

        erase(ALICE);

        assertThat(exists(club)).isFalse();
    }

    @Test
    void erase_shouldDeleteTheCommunity_whenEveryOtherMemberIsDeletedToo() {
        Community club = club(ALICE, "Club");
        join(club, BOB, 1);
        markers.markDeleted(BOB, DELETED_AT).block();

        erase(ALICE);

        assertThat(exists(club)).isFalse();
        assertThat(nodeExists(BOB)).isTrue();
    }

    @Test
    @DisplayName("another admin keeps the community; createdBy stays, as a pseudonym nobody reads")
    void erase_shouldKeepTheCommunityAndItsCreatedBy_whenAnotherAdminRemains() {
        Community club = club(ALICE, "Club");
        join(club, CAROL, 1);
        communities.setRole(club.communityId(), CAROL, CommunityRole.ADMIN).block();

        erase(ALICE);

        assertThat(exists(club)).isTrue();
        assertThat(roleOf(club, CAROL)).isEqualTo(CommunityRole.ADMIN);
        assertThat(communities.findById(club.communityId(), CAROL).block().memberCount()).isEqualTo(1);
        assertThat(count("MATCH (c:Community {communityId: $id, createdBy: $creator}) RETURN count(c) AS c",
                Map.of("id", club.communityId(), "creator", ALICE))).isEqualTo(1);
    }

    @Test
    void erase_shouldDeleteTheInvitationsTheUserSentAndReceived() {
        Community mine = club(ALICE, "Mine");
        join(mine, BOB, 1);
        communities.saveInvitation(mine.communityId(), ALICE, CAROL, "token-sent-aaaaaaaaaa", T0, T0.plusSeconds(60))
                .block();
        Community theirs = club(DAVE, "Theirs");
        communities.saveInvitation(theirs.communityId(), DAVE, ALICE, "token-recv-aaaaaaaaaa", T0, T0.plusSeconds(60))
                .block();
        communities.saveInvitation(theirs.communityId(), DAVE, CAROL, "token-other-aaaaaaaa", T0, T0.plusSeconds(60))
                .block();

        erase(ALICE);

        StepVerifier.create(communities.findInvitation("token-sent-aaaaaaaaaa")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(communities.findInvitation("token-recv-aaaaaaaaaa")).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(communities.findInvitation("token-other-aaaaaaaa").block()).isNotNull();
        assertThat(count("MATCH ()-[i:INVITED {inviterId: $id}]->() RETURN count(i) AS c", Map.of("id", ALICE)))
                .isZero();
    }

    @Test
    @DisplayName("a community the user created and nobody belongs to any more goes; one with members stays")
    void erase_shouldDeleteAnEmptyCommunity_whenTheUserCreatedIt() {
        Community empty = club(ALICE, "Empty");
        communities.removeMember(empty.communityId(), ALICE).block();
        Community handedOver = club(ALICE, "Handed over");
        join(handedOver, BOB, 1);
        communities.setRole(handedOver.communityId(), BOB, CommunityRole.ADMIN).block();
        communities.removeMember(handedOver.communityId(), ALICE).block();

        erase(ALICE);

        assertThat(exists(empty)).isFalse();
        assertThat(exists(handedOver)).isTrue();
    }

    @Test
    @DisplayName("a request between two other users that names the user's event is not theirs to erase")
    void erase_shouldKeepOtherUsersRequests_whenTheyMentionAnEventOfTheUser() {
        relationships.sendFriendRequest(BOB, "carol", 3333, "event-of-alice").block();

        erase(ALICE);

        assertThat(relationships.findIncomingRequests(CAROL).collectList().block())
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.userId()).isEqualTo(BOB);
                    assertThat(request.viaEventId()).isEqualTo("event-of-alice");
                });
    }

    @Test
    @DisplayName("a redelivered USER_DELETED changes nothing, and the first erasure time is kept")
    void erase_shouldLeaveTheGraphUnchangedAndKeepTheFirstErasedAt_whenReplayed() {
        Community club = club(ALICE, "Club");
        join(club, BOB, 1);
        friends(ALICE, CAROL, "carol", 3333);
        erase(ALICE);
        DeletedUserNodeEntity first = markerNodes.findById(ALICE).block();
        long nodes = count("MATCH (n) RETURN count(n) AS c");
        long edges = count("MATCH ()-[r]->() RETURN count(r) AS c");

        StepVerifier.create(service.erase(new UserDeletedEventDTO(ALICE, DELETED_AT.plusSeconds(3600))))
                .expectComplete().verify(Duration.ofSeconds(10));

        DeletedUserNodeEntity replayed = markerNodes.findById(ALICE).block();
        assertThat(replayed.getDeletedAt().toInstant()).isEqualTo(DELETED_AT);
        assertThat(replayed.getErasedAt().toInstant()).isEqualTo(first.getErasedAt().toInstant());
        assertThat(count("MATCH (n) RETURN count(n) AS c")).isEqualTo(nodes);
        assertThat(count("MATCH ()-[r]->() RETURN count(r) AS c")).isEqualTo(edges);
        assertThat(roleOf(club, BOB)).isEqualTo(CommunityRole.ADMIN);
    }

    @Test
    @DisplayName("a late or replayed USER_CREATED or USER_MODIFIED never brings the user back")
    void erase_shouldMakeALateUserCreatedOrModifiedANoOp_whenTheUserIsErased() {
        erase(ALICE);

        StepVerifier.create(relationshipService.writeUser(new UserCreatedEventDTO(ALICE, "alice", 1111, null)))
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(relationshipService.updateUser(
                new UserModifiedEventDTO(ALICE, "alice2", 1111, "Alice", "Martin", null))).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(nodeExists(ALICE)).isFalse();
        assertThat(relationships.existsByUserNameAndHashtag("alice2", 1111).block()).isFalse();
        assertThat(relationships.findAllWithSocialStatus(BOB).map(UserSocialDTO::userId).collectList().block())
                .doesNotContain(ALICE);
    }

    @Test
    @DisplayName("a token still valid after the erasure can write nothing that names the user")
    void erase_shouldLeaveNothingForAStillValidTokenToWriteTo_whenTheUserIsErased() {
        Community publicClub = club(BOB, "Public");
        relationships.sendFriendRequest(DAVE, "alice", 1111, null).block();
        erase(ALICE);

        StepVerifier.create(relationshipService.sendFriendRequest(ALICE, "bob#2222", null))
                .expectError(BusinessException.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(relationshipService.acceptFriendRequest(ALICE, DAVE))
                .expectError(BusinessException.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(communityService.create(ALICE,
                        new CommunityDraft("Ghost club", null, List.of(), CommunityVisibility.PUBLIC)))
                .expectError(BusinessException.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(communityService.invite(ALICE, publicClub.communityId(), BOB))
                .expectError(BusinessException.class).verify(Duration.ofSeconds(10));
        Community joined = communityService.join(ALICE, publicClub.communityId()).block();

        assertThat(joined.isMember()).isFalse();
        assertThat(nodeExists(ALICE)).isFalse();
        assertThat(edgesOf(ALICE)).isZero();
        assertThat(count("MATCH (c:Community {createdBy: $id}) RETURN count(c) AS c", Map.of("id", ALICE))).isZero();
        assertThat(count("MATCH ()-[i:INVITED {inviterId: $id}]->() RETURN count(i) AS c", Map.of("id", ALICE)))
                .isZero();
    }
}
