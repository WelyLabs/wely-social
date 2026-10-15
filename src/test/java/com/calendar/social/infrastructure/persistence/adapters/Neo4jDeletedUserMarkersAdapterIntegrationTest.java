package com.calendar.social.infrastructure.persistence.adapters;

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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the marker Cypher of {@link DeletedUserNodeRepository} on a real Neo4j, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jDeletedUserMarkersAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String USER = "deleted-id";
    private static final Instant FIRST = Instant.parse("2026-10-15T14:03:27.123Z");
    private static final Instant LATER = Instant.parse("2026-10-16T09:00:00Z");

    @Autowired
    private Neo4jDeletedUserMarkersAdapter adapter;

    @Autowired
    private DeletedUserNodeRepository repository;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
    }

    private long markers() {
        return neo4jClient.query("MATCH (d:DeletedUser) RETURN count(d) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    private DeletedUserNodeEntity marker() {
        return repository.findById(USER).block();
    }

    @Test
    void markDeleted_shouldCreateOneMarker_whenNoneExists() {
        StepVerifier.create(adapter.markDeleted(USER, FIRST)).verifyComplete();

        assertThat(markers()).isEqualTo(1);
        assertThat(marker().getDeletedAt().toInstant()).isEqualTo(FIRST);
        assertThat(marker().getErasedAt()).isNull();
    }

    @Test
    @DisplayName("a redelivered USER_DELETED keeps the first deletion time, on one marker")
    void markDeleted_shouldKeepTheFirstDeletedAt_whenReplayed() {
        adapter.markDeleted(USER, FIRST).block();

        StepVerifier.create(adapter.markDeleted(USER, LATER)).verifyComplete();

        assertThat(markers()).isEqualTo(1);
        assertThat(marker().getDeletedAt().toInstant()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a re-applied erasure keeps the first completion time: the provable one")
    void markErased_shouldKeepTheFirstErasedAt_whenReplayed() {
        adapter.markDeleted(USER, FIRST).block();
        adapter.markErased(USER, FIRST).block();

        StepVerifier.create(adapter.markErased(USER, LATER)).verifyComplete();

        assertThat(marker().getErasedAt().toInstant()).isEqualTo(FIRST);
        assertThat(marker().getDeletedAt().toInstant()).isEqualTo(FIRST);
    }

    @Test
    void markErased_shouldDoNothing_whenThereIsNoMarker() {
        StepVerifier.create(adapter.markErased(USER, FIRST)).verifyComplete();

        assertThat(markers()).isZero();
    }
}
