package com.calendar.social.domain.services;

import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.domain.models.ReconciliationReport;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.ports.DeletedUsersSource;
import com.calendar.social.domain.ports.RelationshipRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Drives {@link AccountReconciliationService} through the real erasure on Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class AccountReconciliationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String ALICE = "alice-id";
    private static final String BOB = "bob-id";

    @MockitoBean
    private DeletedUsersSource source;

    @Autowired
    private AccountReconciliationService service;

    @Autowired
    private AccountErasureService erasure;

    @Autowired
    private RelationshipRepository relationships;

    @Autowired
    private DeletedUserNodeRepository markerNodes;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        relationships.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null)).block();
        relationships.save(new UserCreatedEventDTO(BOB, "bob", 2222, null)).block();
        relationships.sendFriendRequest(ALICE, "bob", 2222, null).block();
        relationships.acceptFriendRequest(BOB, ALICE).block();
    }

    private void listed(String userId, Instant deletedAt) {
        when(source.page(isNull(), anyInt()))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(new DeletedUser(userId, deletedAt)), null)));
    }

    private boolean nodeExists(String userId) {
        return neo4jClient.query("MATCH (u:User {userId: $id}) RETURN count(u) AS c").bindAll(Map.of("id", userId))
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block() > 0;
    }

    /** A write that raced the erasure, outside the resurrection guard. */
    private void strayNode(String userId) {
        neo4jClient.query("CREATE (:User {userId: $id, userName: 'alice', hashtag: 1111})")
                .bindAll(Map.of("id", userId)).run().block();
    }

    @Test
    @DisplayName("a USER_DELETED this service never applied is repaired from wely-users' tombstone")
    void reconcile_shouldEraseATombstone_whenItsMarkerIsMissing() {
        Instant deletedAt = Instant.now().minus(Duration.ofDays(30));
        listed(ALICE, deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 1, 0));

        assertThat(nodeExists(ALICE)).isFalse();
        assertThat(relationships.findAllFriends(BOB).collectList().block()).isEmpty();
        DeletedUserNodeEntity marker = markerNodes.findById(ALICE).block();
        assertThat(marker.getDeletedAt().toInstant()).isEqualTo(deletedAt);
        assertThat(marker.getErasedAt()).isNotNull();
    }

    @Test
    @DisplayName("a write that slipped in after the erasure is swept while the deletion is recent")
    void reconcile_shouldEraseAgain_whenTheTombstoneIsUnderTheRecentWindow() {
        Instant deletedAt = Instant.now().minus(Duration.ofHours(1));
        erasure.erase(new UserDeletedEventDTO(ALICE, deletedAt)).block();
        strayNode(ALICE);
        listed(ALICE, deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 1, 0));

        assertThat(nodeExists(ALICE)).isFalse();
    }

    @Test
    @DisplayName("an old tombstone already erased here is left alone: no erasure runs")
    void reconcile_shouldSkipAnErasedTombstone_whenItIsOlderThanTheRecentWindow() {
        Instant deletedAt = Instant.now().minus(Duration.ofDays(30));
        erasure.erase(new UserDeletedEventDTO(ALICE, deletedAt)).block();
        strayNode(ALICE);
        listed(ALICE, deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 0, 0));

        // The stray node proves no erasure ran.
        assertThat(nodeExists(ALICE)).isTrue();
    }
}
