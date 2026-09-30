package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.UserCreatedEventDTO;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Cypher of {@link Neo4jRelationshipRepositoryAdapter} against a real Neo4j.
 *
 * <p>The unit test beside this one stubs the repository, so it never executes a single line of
 * Cypher. These queries are where this service's logic actually lives — the bidirectional
 * relationship status in particular, whose {@code startNode(r)} branches decide whether a
 * pending request reads as sent by me or sent by them. A wrong direction there is invisible to
 * a mock and obvious here.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}. The guard
 * reads an environment variable rather than probing for Docker, because a daemon that is
 * running but wedged makes that probe hang.
 */
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
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("SENT_BY_THEM");
    }

    @Test
    @DisplayName("an accepted request reads FRIENDS from both sides")
    void acceptFriendRequest_shouldMakeTheRelationshipSymmetric() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("FRIENDS");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("FRIENDS");
    }

    @Test
    @DisplayName("only the target can accept, not the sender")
    void acceptFriendRequest_shouldIgnoreTheSenderAcceptingTheirOwnRequest() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();

        StepVerifier.create(adapter.acceptFriendRequest(ALICE, BOB))
                .expectError(BusinessException.class)
                .verify();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
    }

    @Test
    @DisplayName("a rejected request stops reading as pending")
    void rejectFriendRequest_shouldClearThePendingStatus() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
        adapter.rejectFriendRequest(BOB, ALICE).block();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
        assertThat(statusBetween(BOB, ALICE)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("a second request to the same person creates nothing")
    void sendFriendRequest_shouldRefuseADuplicate() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();

        StepVerifier.create(adapter.sendFriendRequest(ALICE, "bob", 2222))
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
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();

        StepVerifier.create(adapter.sendFriendRequest(BOB, "alice", 1111))
                .expectError(BusinessException.class)
                .verify();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("SENT_BY_ME");
    }

    @Test
    @DisplayName("a user cannot befriend themselves")
    void sendFriendRequest_shouldRefuseSelf() {
        StepVerifier.create(adapter.sendFriendRequest(ALICE, "alice", 1111))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    @DisplayName("the friend list holds accepted relationships only")
    void findAllFriends_shouldIgnorePendingAndRejected() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();
        adapter.sendFriendRequest(ALICE, "carol", 3333).block();

        StepVerifier.create(adapter.findAllFriends(ALICE)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("outgoing and incoming requests are told apart")
    void findRequests_shouldSeparateTheTwoDirections() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
        adapter.sendFriendRequest(CAROL, "alice", 1111).block();

        StepVerifier.create(adapter.findOutgoingRequests(ALICE)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findIncomingRequests(ALICE)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("removing a friend takes the relationship with it, and says what it removed")
    void deleteFriendship_shouldRemoveTheEdge() {
        // This is the case that found the bug. The query returned the relationship object, which
        // Spring Data Neo4j cannot map onto a @RelationshipProperties class — so the DELETE ran
        // server-side and the caller still got a TechnicalException. Removing a friend worked and
        // answered 500 at the same time, and no mocked test could see it.
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
        adapter.acceptFriendRequest(BOB, ALICE).block();

        StepVerifier.create(adapter.deleteFriendship(ALICE, BOB))
                .assertNext(deleted -> assertThat(deleted.status()).isEqualTo("ACCEPTED"))
                .verifyComplete();

        assertThat(statusBetween(ALICE, BOB)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("removing works whichever side originally sent the request")
    void deleteFriendship_shouldBeSymmetric() {
        adapter.sendFriendRequest(ALICE, "bob", 2222).block();
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

        assertThat(all).extracting(UserSocialDTO::userId).doesNotContain(ALICE);
    }
}
