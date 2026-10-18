package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import com.calendar.social.infrastructure.persistence.repositories.FriendshipOutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Sort;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves on a real Neo4j that a friendship change and its outbox record commit together, through
 * the ReactiveNeo4jTransactionManager, when CI or integration.tests is set.
 */
@Testcontainers
@SpringBootTest(properties = "app.neo4j.schema-init.enabled=true")
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class RelationshipServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String ALICE = "alice-id";
    private static final String BOB = "bob-id";
    /** Far beyond any millisecond timestamp, so the next version is exactly this plus one. */
    private static final long FAR_FUTURE = 9_000_000_000_000L;

    @Autowired
    private RelationshipService service;

    @Autowired
    private RelationshipRepository relationships;

    @Autowired
    private FriendshipOutboxEventRepository records;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        // The version constraint is what the rollback test trips; it is created at startup.
        await().atMost(Duration.ofSeconds(30)).until(() -> count(
                "SHOW CONSTRAINTS YIELD name WHERE name = 'friendship_outbox_version' RETURN count(*) AS c") == 1);
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        relationships.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null)).block();
        relationships.save(new UserCreatedEventDTO(BOB, "bob", 2222, null)).block();
    }

    private long count(String cypher) {
        return neo4jClient.query(cypher).bindAll(Map.of())
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    private String status() {
        return neo4jClient.query("OPTIONAL MATCH (:User {userId: 'alice-id'})-[r:RELATIONSHIP]-(:User {userId: 'bob-id'}) "
                        + "RETURN coalesce(r.status, 'NONE') AS s")
                .fetchAs(String.class).mappedBy((t, r) -> r.get("s").asString()).one().block();
    }

    private List<FriendshipOutboxEventEntity> all() {
        return records.findAll(Sort.by("version")).collectList().block();
    }

    @Test
    @DisplayName("a request and its acceptance each commit with their record")
    void acceptFriendRequest_shouldCommitTheAcceptanceAndItsRecordTogether() {
        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();

        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(all()).extracting(FriendshipOutboxEventEntity::getType, FriendshipOutboxEventEntity::getFromUserId,
                        FriendshipOutboxEventEntity::getToUserId, FriendshipOutboxEventEntity::getCloseness)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("REQUESTED", ALICE, BOB, null),
                        org.assertj.core.groups.Tuple.tuple("ACCEPTED", BOB, ALICE, "FRIEND"));
    }

    @Test
    @DisplayName("when the record cannot be written, the acceptance is rolled back with it")
    void acceptFriendRequest_shouldRollBackTheAcceptance_whenTheRecordFails() {
        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        // The next version is FAR_FUTURE + 1, and a record already holds it: the CREATE breaks the constraint.
        neo4jClient.query("MATCH (c:FriendshipClock) SET c.value = $v").bind(FAR_FUTURE).to("v").run().block();
        neo4jClient.query("CREATE (:FriendshipOutboxEvent {version: $v})").bind(FAR_FUTURE + 1).to("v").run().block();

        StepVerifier.create(service.acceptFriendRequest(BOB, ALICE)).expectError().verify();

        assertThat(status()).isEqualTo("PENDING");
        assertThat(count("MATCH (e:FriendshipOutboxEvent {type: 'ACCEPTED'}) RETURN count(e) AS c")).isZero();
    }

    @Test
    void deleteFriendship_shouldRecordTheRemoval_withTheClosenessTheFriendshipHad() {
        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();
        service.requestCloseness(ALICE, BOB, Closeness.CLOSE).block();
        service.acceptCloseness(BOB, ALICE).block();

        service.deleteFriendship(BOB, ALICE).block();

        assertThat(status()).isEqualTo("NONE");
        FriendshipOutboxEventEntity removed = all().getLast();
        assertThat(removed.getType()).isEqualTo("REMOVED");
        assertThat(removed.getFromUserId()).isEqualTo(BOB);
        assertThat(removed.getPreviousCloseness()).isEqualTo("CLOSE");
        assertThat(removed.getCloseness()).isNull();
    }

    @Test
    @DisplayName("a proposal records nothing; its acceptance records the step up with the level it left")
    void acceptCloseness_shouldRecordTheStepUp_butNotTheProposal() {
        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();

        service.requestCloseness(ALICE, BOB, Closeness.INTIMATE).block();
        assertThat(all()).hasSize(2);

        service.acceptCloseness(BOB, ALICE).block();

        FriendshipOutboxEventEntity changed = all().getLast();
        assertThat(changed.getType()).isEqualTo("CLOSENESS_CHANGED");
        assertThat(changed.getFromUserId()).isEqualTo(BOB);
        assertThat(changed.getCloseness()).isEqualTo("INTIMATE");
        assertThat(changed.getPreviousCloseness()).isEqualTo("FRIEND");
    }

    @Test
    void rejectFriendRequest_shouldRecordTheRejection() {
        service.sendFriendRequest(ALICE, "bob#2222", null).block();

        service.rejectFriendRequest(BOB, ALICE).block();

        FriendshipOutboxEventEntity rejected = all().getLast();
        assertThat(rejected.getType()).isEqualTo("REJECTED");
        assertThat(rejected.getFromUserId()).isEqualTo(BOB);
        assertThat(rejected.getCloseness()).isNull();
    }
}
