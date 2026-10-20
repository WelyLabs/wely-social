package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.domain.models.UserCreatedEventDTO;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the outbox Cypher of {@link Neo4jFriendshipOutboxAdapter} on a real Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest(properties = "app.friendships.snapshot-name=snapshot-under-test")
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jFriendshipOutboxAdapterIntegrationTest {

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

    @Autowired
    private Neo4jFriendshipOutboxAdapter outbox;

    @Autowired
    private FriendshipOutboxEventRepository records;

    @Autowired
    private Neo4jRelationshipRepositoryAdapter relationships;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        relationships.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null)).block();
        relationships.save(new UserCreatedEventDTO(BOB, "bob", 2222, null)).block();
        relationships.save(new UserCreatedEventDTO(CAROL, "carol", 3333, null)).block();
    }

    private void befriend(String requester, String recipientName, int hashtag, String recipient) {
        relationships.sendFriendRequest(requester, recipientName, hashtag, null).block();
        relationships.acceptFriendRequest(recipient, requester).block();
    }

    private List<FriendshipOutboxEventEntity> all() {
        return records.findAll(Sort.by("version")).collectList().block();
    }

    private long count(String cypher, Map<String, Object> params) {
        return neo4jClient.query(cypher).bindAll(params)
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    @Test
    @DisplayName("an acceptance is recorded with the pair's state as the graph holds it: FRIEND, since acceptedAt")
    void append_shouldRecordThePairsStateAfterTheChange_whenTheyAreFriends() {
        long before = Instant.now().toEpochMilli();
        befriend(ALICE, "bob", 2222, BOB);

        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.ACCEPTED, BOB, ALICE)).block();

        FriendshipOutboxEventEntity record = all().getFirst();
        assertThat(record.getType()).isEqualTo("ACCEPTED");
        assertThat(record.getFromUserId()).isEqualTo(BOB);
        assertThat(record.getFromUserName()).isEqualTo("bob");
        assertThat(record.getToUserId()).isEqualTo(ALICE);
        assertThat(record.getPairKey()).isEqualTo("alice-id:bob-id");
        assertThat(record.getCloseness()).isEqualTo("FRIEND");
        assertThat(record.getPreviousCloseness()).isNull();
        assertThat(record.getSince()).isNotNull();
        assertThat(record.getOccurredAt()).isNotNull();
        assertThat(record.getPublishedAt()).isNull();
        assertThat(record.getVersion()).isGreaterThanOrEqualTo(before);
        assertThat(count("MATCH (e:FriendshipOutboxEvent:Unpublished) RETURN count(e) AS c", Map.of())).isEqualTo(1);
    }

    @Test
    void append_shouldLeaveClosenessAndSinceNull_whenTheTwoAreNotFriends() {
        relationships.sendFriendRequest(ALICE, "bob", 2222, null).block();

        outbox.append(new FriendshipEvent(FriendshipEventType.REMOVED, BOB, ALICE, Closeness.CLOSE)).block();

        FriendshipOutboxEventEntity record = all().getFirst();
        assertThat(record.getCloseness()).isNull();
        assertThat(record.getSince()).isNull();
        assertThat(record.getPreviousCloseness()).isEqualTo("CLOSE");
        assertThat(record.getPairKey()).isEqualTo("alice-id:bob-id");
    }

    @Test
    void append_shouldRecordTheAgreedCloseness_afterAStepUp() {
        befriend(ALICE, "bob", 2222, BOB);
        relationships.updateCloseness(ALICE, new Friendship(BOB, Closeness.INTIMATE, null, false, 0, null), 0).block();

        outbox.append(new FriendshipEvent(FriendshipEventType.CLOSENESS_CHANGED, ALICE, BOB, Closeness.FRIEND)).block();

        assertThat(all().getFirst().getCloseness()).isEqualTo("INTIMATE");
    }

    @Test
    @DisplayName("versions keep rising, even for a pair deleted and re-created: the clock outlives the relationship")
    void append_shouldHandOutRisingVersions_acrossADeleteAndReCreate() {
        befriend(ALICE, "bob", 2222, BOB);
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.ACCEPTED, BOB, ALICE)).block();
        relationships.deleteFriendship(ALICE, BOB).block();
        outbox.append(new FriendshipEvent(FriendshipEventType.REMOVED, ALICE, BOB, Closeness.FRIEND)).block();
        befriend(ALICE, "bob", 2222, BOB);
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.ACCEPTED, BOB, ALICE)).block();

        List<Long> versions = all().stream().map(FriendshipOutboxEventEntity::getVersion).toList();
        assertThat(versions).hasSize(3).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the snapshot enqueues one SNAPSHOT per accepted pair, oriented like ACCEPTED, then only once")
    void enqueueSnapshotOnce_shouldEnqueueEachAcceptedPairOnce() {
        befriend(ALICE, "bob", 2222, BOB);
        befriend(CAROL, "alice", 1111, ALICE);
        relationships.sendFriendRequest(BOB, "carol", 3333, null).block();

        StepVerifier.create(outbox.enqueueSnapshotOnce()).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.enqueueSnapshotOnce()).expectComplete().verify(Duration.ofSeconds(10));

        List<FriendshipOutboxEventEntity> snapshot = all();
        assertThat(snapshot).hasSize(2).allSatisfy(record -> {
            assertThat(record.getType()).isEqualTo("SNAPSHOT");
            assertThat(record.getCloseness()).isEqualTo("FRIEND");
            assertThat(record.getPreviousCloseness()).isNull();
            assertThat(record.getSince()).isNotNull();
        });
        assertThat(snapshot).extracting(FriendshipOutboxEventEntity::getFromUserId, FriendshipOutboxEventEntity::getToUserId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(BOB, ALICE),
                        org.assertj.core.groups.Tuple.tuple(ALICE, CAROL));
        assertThat(snapshot).extracting(FriendshipOutboxEventEntity::getVersion).doesNotHaveDuplicates();
        assertThat(count("MATCH (m:Migration {name: $name}) RETURN count(m) AS c",
                Map.of("name", "snapshot-under-test"))).isEqualTo(1);
    }

    @Test
    void enqueueSnapshotOnce_shouldOnlyLeaveTheMarker_whenThereIsNoFriendship() {
        StepVerifier.create(outbox.enqueueSnapshotOnce()).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(all()).isEmpty();
        assertThat(count("MATCH (m:Migration) RETURN count(m) AS c", Map.of())).isEqualTo(1);
    }

    @Test
    @DisplayName("a change recorded after the snapshot gets a higher version than every snapshot record")
    void enqueueSnapshotOnce_shouldNumberBelowTheChangesThatFollow() {
        befriend(ALICE, "bob", 2222, BOB);
        befriend(CAROL, "alice", 1111, ALICE);
        outbox.enqueueSnapshotOnce().block();

        outbox.append(new FriendshipEvent(FriendshipEventType.REMOVED, ALICE, BOB, Closeness.FRIEND)).block();

        assertThat(all().getLast().getType()).isEqualTo("REMOVED");
    }

    @Test
    void findPending_shouldListUnpublishedRecordsInVersionOrder_upToTheLimit() {
        befriend(ALICE, "bob", 2222, BOB);
        for (int i = 0; i < 3; i++) {
            outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.ACCEPTED, BOB, ALICE)).block();
        }
        List<Long> versions = all().stream().map(FriendshipOutboxEventEntity::getVersion).toList();
        outbox.markPublished(versions.getFirst()).block();

        StepVerifier.create(outbox.findPending(1)).assertNext(record ->
                assertThat(record.getVersion()).isEqualTo(versions.get(1))).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.findPending(10)).expectNextCount(2).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldStampTheRecord_andTakeItOutOfThePending() {
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, BOB)).block();
        long version = all().getFirst().getVersion();

        StepVerifier.create(outbox.markPublished(version)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.markPublished(version)).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(all().getFirst().getPublishedAt()).isNotNull();
        StepVerifier.create(outbox.findPending(10)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordFailure_shouldCountTheAttempt_andKeepTheRecordPending() {
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, BOB)).block();
        long version = all().getFirst().getVersion();

        outbox.recordFailure(version, "broker down").block();
        outbox.recordFailure(version, "still down").block();

        FriendshipOutboxEventEntity record = all().getFirst();
        assertThat(record.getAttempts()).isEqualTo(2);
        assertThat(record.getLastError()).isEqualTo("still down");
        StepVerifier.create(outbox.findPending(10)).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a parked record leaves the pending ones for good, with its error and attempt kept for inspection")
    void park_shouldTakeTheRecordOutOfThePending_andKeepWhy() {
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, BOB)).block();
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, CAROL)).block();
        long version = all().getFirst().getVersion();
        outbox.recordFailure(version, "broker down").block();

        StepVerifier.create(outbox.park(version, "record too large")).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.park(version, "again")).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));

        FriendshipOutboxEventEntity parked = all().getFirst();
        assertThat(parked.getParkedAt()).isNotNull();
        assertThat(parked.getPublishedAt()).isNull();
        assertThat(parked.getAttempts()).isEqualTo(2);
        assertThat(parked.getLastError()).isEqualTo("record too large");
        assertThat(count("MATCH (e:FriendshipOutboxEvent:Parked) RETURN count(e) AS c", Map.of())).isEqualTo(1);
        StepVerifier.create(outbox.findPending(10)).assertNext(record ->
                assertThat(record.getToUserId()).isEqualTo(CAROL)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the purge deletes published and parked records past the cutoff, never a pending one")
    void purgeSettledBefore_shouldDeleteOnlyOldPublishedOrParkedRecords() {
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, BOB)).block();
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, ALICE, CAROL)).block();
        outbox.append(FriendshipEvent.betweenNonFriends(FriendshipEventType.REQUESTED, BOB, CAROL)).block();
        List<FriendshipOutboxEventEntity> records = all();
        outbox.markPublished(records.get(0).getVersion()).block();
        outbox.park(records.get(1).getVersion(), "record too large").block();

        StepVerifier.create(outbox.purgeSettledBefore(Instant.now().minus(Duration.ofDays(1)), 100))
                .expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.purgeSettledBefore(Instant.now().plus(Duration.ofMinutes(1)), 100))
                .expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(all()).singleElement().satisfies(record -> assertThat(record.getFromUserId()).isEqualTo(BOB));
    }
}
