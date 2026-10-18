package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.RelationStatus;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.domain.models.UserSocialDTO;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Cypher of {@link Neo4jRelationshipRepositoryAdapter} on a real Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jRelationshipRepositoryAdapterIntegrationTest {

    /**
     * The password matches the one in {@code application-test.properties}. Running the container
     * {@code withoutAuthentication()} while the profile still supplied credentials left the two
     * disagreeing, and the class took twenty-three minutes in CI rather than one.
     */
    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private Neo4jRelationshipRepositoryAdapter adapter;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @Autowired
    private Neo4jDeletedUserMarkersAdapter markers;

    private static final String ALICE = "alice-id";
    private static final String BOB = "bob-id";
    private static final String CAROL = "carol-id";

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();

        given(ALICE, "alice", 1111);
        given(BOB, "bob", 2222);
        given(CAROL, "carol", 3333);
    }

    private void given(String userId, String userName, int hashtag) {
        adapter.save(new UserCreatedEventDTO(userId, userName, hashtag, null)).block();
    }

    private String statusBetween(String viewer, String otherUserId) {
        List<UserSocialDTO> all = adapter.findAllWithSocialStatus(viewer).collectList().block();
        return all.stream()
                .filter(u -> u.userId().equals(otherUserId))
                .map(UserSocialDTO::relationStatus)
                .findFirst()
                .orElseThrow(() -> new AssertionError(otherUserId + " absent de la liste"));
    }

    @Test
    @DisplayName("a user event creates exactly one node")
    void save_shouldCreateOneNode() {
        Long count = neo4jClient.query("MATCH (u:User) RETURN count(u) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("replaying the same user event converges on one node")
    void save_shouldBeIdempotentOnReplay() {
        // Kafka is at-least-once and a consumer group can be reset, so USER_CREATED is
        // redelivered. The MERGE is what stops a replay adding a second node — something a
        // stubbed repository cannot demonstrate.
        given(ALICE, "alice", 1111);
        given(ALICE, "alice-renamed", 1111);

        Long count = neo4jClient.query("MATCH (u:User {userId: $id}) RETURN count(u) AS c")
                .bind(ALICE).to("id")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("with no relationship, both sides read NONE")
    void findAllWithSocialStatus_shouldReportNoneWithoutARelationship() {
        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("a pending request reads SENT_BY_ME for the sender and SENT_BY_THEM for the target")
    void findAllWithSocialStatus_shouldDistinguishTheDirectionOfAPendingRequest() {
        // The startNode(r) branches. Getting them the wrong way round would show every
        // incoming request as one the user sent — and no mock would notice.
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("SENT_BY_THEM");
    }

    @Test
    @DisplayName("an accepted request reads FRIENDS from both sides")
    void acceptFriendRequest_shouldMakeTheRelationshipSymmetric() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("FRIENDS");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("FRIENDS");
    }

    @Test
    @DisplayName("only the target can accept, not the sender")
    void acceptFriendRequest_shouldIgnoreTheSenderAcceptingTheirOwnRequest() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        StepVerifier.create(adapter.acceptFriendRequest(ALICE, BOB))
                .expectError(BusinessException.class)
                .verify();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
    }

    @Test
    @DisplayName("a rejected request stops reading as pending")
    void rejectFriendRequest_shouldClearThePendingStatus() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.rejectFriendRequest(BOB, ALICE).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("a second request to the same person creates nothing")
    void sendFriendRequest_shouldRefuseADuplicate() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        StepVerifier.create(adapter.sendFriendRequest(ALICE, "bob", 2222, null))
                .expectError(BusinessException.class)
                .verify();

        Long relationships = neo4jClient
                .query("MATCH (:User {userId: $a})-[r:RELATIONSHIP]-(:User {userId: $b}) RETURN count(r) AS c")
                .bindAll(Map.of("a", ALICE, "b", BOB))
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();

        assertThat(relationships).isEqualTo(1);
    }

    @Test
    @DisplayName("a request back to someone who already asked creates nothing either")
    void sendFriendRequest_shouldRefuseARequestInTheOppositeDirection() {
        // NOT (me)-[:RELATIONSHIP]-(target) is undirected on purpose: answering a pending
        // request with another request would leave two edges and an undefined status.
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        StepVerifier.create(adapter.sendFriendRequest(BOB, "alice", 1111, null))
                .expectError(BusinessException.class)
                .verify();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
    }

    @Test
    @DisplayName("a user cannot befriend themselves")
    void sendFriendRequest_shouldRefuseSelf() {
        StepVerifier.create(adapter.sendFriendRequest(ALICE, "alice", 1111, null))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    @DisplayName("the friend list holds accepted relationships only")
    void findAllFriends_shouldIgnorePendingAndRejected() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();
        adapter.sendFriendRequest(ALICE, "carol", 3333, null).block();

        StepVerifier.create(adapter.findAllFriends(ALICE)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("outgoing and incoming requests are told apart")
    void findRequests_shouldSeparateTheTwoDirections() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.sendFriendRequest(CAROL, "alice", 1111, null).block();

        StepVerifier.create(adapter.findOutgoingRequests(ALICE)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findIncomingRequests(ALICE)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("a request sent from an event keeps the event, on both sides, while it is pending")
    void sendFriendRequest_shouldKeepTheEventItWasSentFrom() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, "event-1").block();

        StepVerifier.create(adapter.findOutgoingRequests(ALICE))
                .assertNext(request -> assertThat(request.viaEventId()).isEqualTo("event-1"))
                .verifyComplete();
        StepVerifier.create(adapter.findIncomingRequests(BOB))
                .assertNext(request -> {
                    assertThat(request.userId()).isEqualTo(ALICE);
                    assertThat(request.hashtag()).isEqualTo(1111);
                    assertThat(request.viaEventId()).isEqualTo("event-1");
                })
                .verifyComplete();
        assertThat(adapter.findAllWithSocialStatus(BOB).collectList().block())
                .filteredOn(user -> user.userId().equals(ALICE))
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.viaEventId()).isEqualTo("event-1");
                    assertThat(user.hashtag()).isEqualTo(1111);
                });
    }

    @Test
    @DisplayName("once accepted, the listing no longer reports the event")
    void findAllWithSocialStatus_shouldDropTheEventOnceFriends() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, "event-1").block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        assertThat(adapter.findAllWithSocialStatus(BOB).collectList().block())
                .filteredOn(user -> user.userId().equals(ALICE))
                .singleElement()
                .satisfies(user -> assertThat(user.viaEventId()).isNull());
    }

    @Test
    @DisplayName("removing a friend takes the relationship with it, and says what it removed")
    void deleteFriendship_shouldRemoveTheEdge() {
        // This is the case that found the bug. The query returned the relationship object, which
        // Spring Data Neo4j cannot map onto a @RelationshipProperties class — so the DELETE ran
        // server-side and the caller still got a TechnicalException. Removing a friend worked and
        // answered 500 at the same time, and no mocked test could see it.
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        StepVerifier.create(adapter.deleteFriendship(ALICE, BOB))
                .assertNext(deleted -> {
                    assertThat(deleted.status()).isEqualTo("ACCEPTED");
                    // Read before the DELETE: the closeness the friendship had, for the REMOVED record.
                    assertThat(deleted.closeness()).isEqualTo(Closeness.FRIEND);
                })
                .verifyComplete();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("removing works whichever side originally sent the request")
    void deleteFriendship_shouldBeSymmetric() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        StepVerifier.create(adapter.deleteFriendship(BOB, ALICE)).expectNextCount(1).verifyComplete();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("removing someone who is not a friend is refused")
    void deleteFriendship_shouldRefuseANonFriend() {
        StepVerifier.create(adapter.deleteFriendship(ALICE, CAROL))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    @DisplayName("a user tag is recognised only with the right hashtag")
    void existsByUserNameAndHashtag_shouldRequireBothParts() {
        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 1111))
                .expectNext(true).verifyComplete();
        StepVerifier.create(adapter.existsByUserNameAndHashtag("alice", 9999))
                .expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("the status list never includes the viewer")
    void findAllWithSocialStatus_shouldExcludeTheCaller() {
        List<UserSocialDTO> all = adapter.findAllWithSocialStatus(ALICE).collectList().block();

        // The other two, and only those. Asserting the absence of ALICE on its own would pass
        // just as happily on an empty list — which is the failure this is meant to catch.
        assertThat(all).extracting(UserSocialDTO::userId).containsExactlyInAnyOrder(BOB, CAROL);
    }

    @Test
    @DisplayName("deleting a user removes its node and its relationships, and leaves the others alone")
    void delete_shouldDetachDeleteTheNode() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        adapter.delete(ALICE).block();

        Long users = neo4jClient.query("MATCH (u:User) RETURN count(u) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
        Long relationships = neo4jClient.query("MATCH ()-[r]-() RETURN count(r) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
        assertThat(users).isEqualTo(2);
        assertThat(relationships).isZero();
        assertThat(adapter.findAllFriends(BOB).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("deleting a user that does not exist is not an error: USER_DELETED can be replayed")
    void delete_shouldBeIdempotent() {
        adapter.delete("nobody").block();
        adapter.delete(ALICE).block();

        StepVerifier.create(adapter.delete(ALICE)).verifyComplete();
    }

    private long usersWithId(String userId) {
        return neo4jClient.query("MATCH (u:User {userId: $id}) RETURN count(u) AS c")
                .bind(userId).to("id")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    private long relationshipsOf(String userId) {
        return neo4jClient.query("MATCH (:User {userId: $id})-[r:RELATIONSHIP]-() RETURN count(r) AS c")
                .bind(userId).to("id")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    @Test
    @DisplayName("a replayed USER_CREATED never brings back a user with a deletion marker")
    void save_shouldNotRecreateTheNode_whenTheUserHasADeletionMarker() {
        adapter.delete(ALICE).block();
        markers.markDeleted(ALICE, Instant.parse("2026-10-15T14:00:00Z")).block();

        StepVerifier.create(adapter.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null))).verifyComplete();

        assertThat(usersWithId(ALICE)).isZero();
    }

    @Test
    @DisplayName("a late USER_MODIFIED never heals the node of a user with a deletion marker")
    void update_shouldNotRecreateTheNode_whenTheUserHasADeletionMarker() {
        adapter.delete(ALICE).block();
        markers.markDeleted(ALICE, Instant.parse("2026-10-15T14:00:00Z")).block();

        StepVerifier.create(adapter.update(new UserModifiedEventDTO(ALICE, "alice2", 1111, "A", "M", null)))
                .verifyComplete();

        assertThat(usersWithId(ALICE)).isZero();
        assertThat(adapter.existsByUserNameAndHashtag("alice2", 1111).block()).isFalse();
    }

    @Test
    @DisplayName("while the erasure is pending, a marked node is not renamed either")
    void update_shouldLeaveTheNodeUnchanged_whenTheUserHasADeletionMarker() {
        markers.markDeleted(ALICE, Instant.parse("2026-10-15T14:00:00Z")).block();

        StepVerifier.create(adapter.update(new UserModifiedEventDTO(ALICE, "alice2", 1111, "A", "M", null)))
                .verifyComplete();

        assertThat(adapter.existsByUserNameAndHashtag("alice", 1111).block()).isTrue();
        assertThat(adapter.existsByUserNameAndHashtag("alice2", 1111).block()).isFalse();
    }

    @Test
    @DisplayName("without a marker, USER_MODIFIED still heals a node USER_CREATED never made")
    void update_shouldStillHealAMissingNode_whenThereIsNoMarker() {
        StepVerifier.create(adapter.update(new UserModifiedEventDTO("dave-id", "dave", 4444, "D", "V", null)))
                .verifyComplete();

        assertThat(usersWithId("dave-id")).isEqualTo(1);
        assertThat(adapter.existsByUserNameAndHashtag("dave", 4444).block()).isTrue();
    }

    @Test
    @DisplayName("deleting a user takes the requests it sent and the ones it received out of both inboxes")
    void delete_shouldRemovePendingRequestsInBothDirections() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, "event-1").block();
        adapter.sendFriendRequest(CAROL, "alice", 1111, null).block();

        adapter.delete(ALICE).block();

        assertThat(adapter.findIncomingRequests(BOB).collectList().block()).isEmpty();
        assertThat(adapter.findOutgoingRequests(CAROL).collectList().block()).isEmpty();
        assertThat(relationshipsOf(BOB)).isZero();
        assertThat(relationshipsOf(CAROL)).isZero();
    }

    @Test
    void delete_shouldRemoveRejectedRelationshipsInBothDirections() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.rejectFriendRequest(BOB, ALICE).block();
        adapter.sendFriendRequest(CAROL, "alice", 1111, null).block();
        adapter.rejectFriendRequest(ALICE, CAROL).block();

        adapter.delete(ALICE).block();

        assertThat(relationshipsOf(BOB)).isZero();
        assertThat(relationshipsOf(CAROL)).isZero();
    }

    private static Friendship withoutSince(Friendship f) {
        return new Friendship(f.friendId(), f.closeness(), f.proposal(), f.proposedByFriend(), f.version(), null);
    }

    private void friends(String sender, String target, String targetName, int targetHashtag) {
        adapter.sendFriendRequest(sender, targetName, targetHashtag, null).block();
        adapter.acceptFriendRequest(target, sender).block();
    }

    @Test
    @DisplayName("a friendship without closeness reads as FRIEND, version 0, from both sides")
    void findFriendship_shouldDefaultToFriend() {
        friends(ALICE, BOB, "bob", 2222);

        assertThat(withoutSince(adapter.findFriendship(ALICE, BOB).block()))
                .isEqualTo(new Friendship(BOB, Closeness.FRIEND, null, false, 0, null));
        assertThat(withoutSince(adapter.findFriendship(BOB, ALICE).block()))
                .isEqualTo(new Friendship(ALICE, Closeness.FRIEND, null, false, 0, null));
    }

    @Test
    @DisplayName("a pending request is not a friendship")
    void findFriendship_shouldIgnorePendingRequests() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        StepVerifier.create(adapter.findFriendship(ALICE, BOB)).verifyComplete();
    }

    @Test
    @DisplayName("a proposal reads as mine from one side and as the friend's from the other")
    void updateCloseness_shouldStoreWhoProposed() {
        friends(ALICE, BOB, "bob", 2222);

        Friendship written = adapter.updateCloseness(ALICE,
                new Friendship(BOB, Closeness.FRIEND, Closeness.INTIMATE, false, 0, null), 0).block();

        assertThat(withoutSince(written)).isEqualTo(new Friendship(BOB, Closeness.FRIEND, Closeness.INTIMATE, false, 1, null));
        assertThat(withoutSince(adapter.findFriendship(BOB, ALICE).block()))
                .isEqualTo(new Friendship(ALICE, Closeness.FRIEND, Closeness.INTIMATE, true, 1, null));
    }

    @Test
    @DisplayName("clearing the proposal removes it from the relationship")
    void updateCloseness_shouldClearTheProposal() {
        friends(ALICE, BOB, "bob", 2222);
        adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.FRIEND, Closeness.CLOSE, false, 0, null), 0).block();

        adapter.updateCloseness(BOB, new Friendship(ALICE, Closeness.CLOSE, null, false, 1, null), 1).block();

        assertThat(withoutSince(adapter.findFriendship(ALICE, BOB).block()))
                .isEqualTo(new Friendship(BOB, Closeness.CLOSE, null, false, 2, null));
    }

    @Test
    @DisplayName("a write based on a stale version matches nothing")
    void updateCloseness_shouldRefuseAStaleVersion() {
        // Two sides answering at once both read version 0; only the first write may land.
        friends(ALICE, BOB, "bob", 2222);
        adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.FRIEND, Closeness.CLOSE, false, 0, null), 0).block();

        StepVerifier.create(adapter.updateCloseness(BOB, new Friendship(ALICE, Closeness.INTIMATE, null, false, 0, null), 0))
                .verifyComplete();
        assertThat(adapter.findFriendship(ALICE, BOB).block().closeness()).isEqualTo(Closeness.FRIEND);
    }

    @Test
    @DisplayName("friend summaries list only accepted friends, with their closeness")
    void findFriendSummaries_shouldListFriendsWithTheirCloseness() {
        friends(ALICE, BOB, "bob", 2222);
        adapter.sendFriendRequest(ALICE, "carol", 3333, null).block();
        adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.CLOSE, null, false, 0, null), 0).block();

        assertThat(adapter.findFriendSummaries(ALICE).collectList().block())
                .containsExactly(new FriendSummary(BOB, "bob", 2222, null, Closeness.CLOSE, null, false));
    }

    @Test
    @DisplayName("removing a friend forgets the closeness: becoming friends again starts over")
    void deleteFriendship_shouldForgetTheCloseness() {
        friends(ALICE, BOB, "bob", 2222);
        adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.INTIMATE, null, false, 0, null), 0).block();

        adapter.deleteFriendship(ALICE, BOB).block();
        friends(BOB, ALICE, "alice", 1111);

        assertThat(adapter.findFriendship(ALICE, BOB).block().closeness()).isEqualTo(Closeness.FRIEND);
    }

    private static final Instant CREATED = Instant.parse("2024-05-06T07:08:09Z");
    private static final Instant ACCEPTED = Instant.parse("2025-01-02T03:04:05Z");

    /** Pins the relationship's dates; a null acceptedAt removes it, as on friendships older than the field. */
    private void setDates(String a, String b, Instant createdAt, Instant acceptedAt) {
        neo4jClient.query("MATCH (:User {userId: $a})-[r:RELATIONSHIP]-(:User {userId: $b}) "
                        + "SET r.createdAt = datetime($createdAt), "
                        + "    r.acceptedAt = CASE WHEN $acceptedAt IS NULL THEN null ELSE datetime($acceptedAt) END")
                .bind(a).to("a").bind(b).to("b")
                .bind(createdAt.toString()).to("createdAt")
                .bind(acceptedAt == null ? null : acceptedAt.toString()).to("acceptedAt")
                .run().block();
    }

    @Test
    void findFriendship_shouldDateTheFriendshipFromItsAcceptance() {
        friends(ALICE, BOB, "bob", 2222);
        setDates(ALICE, BOB, CREATED, ACCEPTED);

        assertThat(adapter.findFriendship(BOB, ALICE).block().since()).isEqualTo(ACCEPTED);
    }

    @Test
    void findFriendship_shouldFallBackToTheCreationDate_whenAcceptedAtIsMissing() {
        friends(ALICE, BOB, "bob", 2222);
        setDates(ALICE, BOB, CREATED, null);

        assertThat(adapter.findFriendship(ALICE, BOB).block().since()).isEqualTo(CREATED);
    }

    @Test
    void updateCloseness_shouldReturnTheFriendshipDate() {
        friends(ALICE, BOB, "bob", 2222);
        setDates(ALICE, BOB, CREATED, ACCEPTED);

        assertThat(adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.CLOSE, null, false, 0, null), 0)
                .block().since()).isEqualTo(ACCEPTED);
    }

    @Test
    void findPerson_shouldReadNone_whenNoRelationship() {
        assertThat(adapter.findPerson(ALICE, BOB).block())
                .isEqualTo(new Person(BOB, "bob", 2222, null, RelationStatus.NONE, null, null, false, null));
    }

    @Test
    void findPerson_shouldTellTheDirectionOfAPendingRequest() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();

        Person seenByAlice = adapter.findPerson(ALICE, BOB).block();
        Person seenByBob = adapter.findPerson(BOB, ALICE).block();

        assertThat(seenByAlice.relationStatus()).isEqualTo(RelationStatus.SENT_BY_ME);
        assertThat(seenByBob.relationStatus()).isEqualTo(RelationStatus.SENT_BY_THEM);
        // Not friends yet: no level, no date.
        assertThat(seenByBob.closeness()).isNull();
        assertThat(seenByBob.since()).isNull();
    }

    @Test
    void findPerson_shouldReadFriendsWithTheLevelsAndTheDate_fromBothSides() {
        friends(ALICE, BOB, "bob", 2222);
        setDates(ALICE, BOB, CREATED, ACCEPTED);
        adapter.updateCloseness(ALICE, new Friendship(BOB, Closeness.CLOSE, Closeness.INTIMATE, false, 0, null), 0)
                .block();

        assertThat(adapter.findPerson(ALICE, BOB).block()).isEqualTo(new Person(
                BOB, "bob", 2222, null, RelationStatus.FRIENDS, Closeness.CLOSE, Closeness.INTIMATE, false, ACCEPTED));
        assertThat(adapter.findPerson(BOB, ALICE).block()).isEqualTo(new Person(
                ALICE, "alice", 1111, null, RelationStatus.FRIENDS, Closeness.CLOSE, Closeness.INTIMATE, true, ACCEPTED));
    }

    @Test
    void findPerson_shouldDefaultTheClosenessToFriend() {
        friends(ALICE, BOB, "bob", 2222);

        Person bob = adapter.findPerson(ALICE, BOB).block();

        assertThat(bob.closeness()).isEqualTo(Closeness.FRIEND);
        assertThat(bob.proposal()).isNull();
        assertThat(bob.since()).isNotNull();
    }

    @Test
    void findPerson_shouldFallBackToTheCreationDate_whenAcceptedAtIsMissing() {
        friends(ALICE, BOB, "bob", 2222);
        setDates(ALICE, BOB, CREATED, null);

        assertThat(adapter.findPerson(ALICE, BOB).block().since()).isEqualTo(CREATED);
    }

    @Test
    void findPerson_shouldReadNone_whenTheRequestWasRejected() {
        adapter.sendFriendRequest(ALICE, "bob", 2222, null).block();
        adapter.rejectFriendRequest(BOB, ALICE).block();

        assertThat(adapter.findPerson(ALICE, BOB).block().relationStatus()).isEqualTo(RelationStatus.NONE);
        assertThat(adapter.findPerson(BOB, ALICE).block().relationStatus()).isEqualTo(RelationStatus.NONE);
    }

    @Test
    void findPerson_shouldPreferTheAcceptedRelationship_whenSeveralExist() {
        // The write path never creates two edges, but the read must not depend on edge order.
        friends(ALICE, BOB, "bob", 2222);
        neo4jClient.query("MATCH (a:User {userId: $a}), (b:User {userId: $b}) "
                        + "CREATE (b)-[:RELATIONSHIP {status: 'PENDING', createdAt: datetime()}]->(a) "
                        + "CREATE (b)-[:RELATIONSHIP {status: 'REJECTED', createdAt: datetime()}]->(a)")
                .bind(ALICE).to("a").bind(BOB).to("b").run().block();

        assertThat(adapter.findPerson(ALICE, BOB).block().relationStatus()).isEqualTo(RelationStatus.FRIENDS);
    }

    @Test
    void findPerson_shouldPreferAPendingRequestOverARejectedOne() {
        neo4jClient.query("MATCH (a:User {userId: $a}), (b:User {userId: $b}) "
                        + "CREATE (a)-[:RELATIONSHIP {status: 'REJECTED', createdAt: datetime()}]->(b) "
                        + "CREATE (b)-[:RELATIONSHIP {status: 'PENDING', createdAt: datetime()}]->(a)")
                .bind(ALICE).to("a").bind(BOB).to("b").run().block();

        assertThat(adapter.findPerson(ALICE, BOB).block().relationStatus()).isEqualTo(RelationStatus.SENT_BY_THEM);
    }

    @Test
    void findPerson_shouldReadSelf_forTheCallersOwnProfile() {
        assertThat(adapter.findPerson(ALICE, ALICE).block())
                .isEqualTo(new Person(ALICE, "alice", 1111, null, RelationStatus.SELF, null, null, false, null));
    }

    @Test
    void findPerson_shouldBeEmpty_whenTheUserIsUnknown() {
        StepVerifier.create(adapter.findPerson(ALICE, "nobody")).verifyComplete();
    }

    @Test
    void existsById_shouldTellKnownFromUnknownUsers() {
        StepVerifier.create(adapter.existsById(BOB)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.existsById("nobody")).expectNext(false).verifyComplete();
    }

    @Test
    void findMutualFriends_shouldListFriendsOfBoth_inEitherDirection() {
        friends(ALICE, CAROL, "carol", 3333);
        friends(CAROL, BOB, "bob", 2222);

        assertThat(adapter.findMutualFriends(ALICE, BOB, 50).collectList().block())
                .containsExactly(new UserNodeDTO(CAROL, "carol", 3333, null));
        assertThat(adapter.findMutualFriends(BOB, ALICE, 50).collectList().block())
                .extracting(UserNodeDTO::userId).containsExactly(CAROL);
    }

    @Test
    void findMutualFriends_shouldExcludeTheTwoPeople_whenTheyAreFriends() {
        friends(ALICE, BOB, "bob", 2222);
        friends(ALICE, CAROL, "carol", 3333);
        friends(BOB, CAROL, "carol", 3333);

        assertThat(adapter.findMutualFriends(ALICE, BOB, 50).collectList().block())
                .extracting(UserNodeDTO::userId).containsExactly(CAROL);
    }

    @Test
    void findMutualFriends_shouldIgnorePendingRelationships() {
        friends(ALICE, CAROL, "carol", 3333);
        adapter.sendFriendRequest(CAROL, "bob", 2222, null).block();

        assertThat(adapter.findMutualFriends(ALICE, BOB, 50).collectList().block()).isEmpty();
    }

    @Test
    void findMutualFriends_shouldSortByUserNameAndApplyTheLimit() {
        given("dave-id", "dave", 4444);
        given("erin-id", "erin", 5555);
        for (String[] friend : new String[][]{{"erin-id", "erin", "5555"}, {CAROL, "carol", "3333"},
                {"dave-id", "dave", "4444"}}) {
            friends(ALICE, friend[0], friend[1], Integer.parseInt(friend[2]));
            friends(BOB, friend[0], friend[1], Integer.parseInt(friend[2]));
        }

        assertThat(adapter.findMutualFriends(ALICE, BOB, 50).collectList().block())
                .extracting(UserNodeDTO::userName).containsExactly("carol", "dave", "erin");
        assertThat(adapter.findMutualFriends(ALICE, BOB, 2).collectList().block())
                .extracting(UserNodeDTO::userName).containsExactly("carol", "dave");
    }
}
