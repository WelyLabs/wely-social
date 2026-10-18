package com.calendar.social.infrastructure.persistence.schema;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Runs the constraint statement on a real Neo4j 5, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest(properties = "app.neo4j.schema-init.enabled=true")
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jSchemaInitializerIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private Neo4jSchemaInitializer initializer;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
    }

    private long constraints() {
        return neo4jClient.query("SHOW CONSTRAINTS YIELD name WHERE name = 'deleted_user_id' RETURN count(*) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    @Test
    @DisplayName("the application's start creates the constraint, which then refuses a second marker per user")
    void start_shouldCreateTheConstraint_whenItDoesNotExist() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(constraints()).isEqualTo(1));

        neo4jClient.query("CREATE (:DeletedUser {userId: 'twice'})").run().block();
        StepVerifier.create(neo4jClient.query("CREATE (:DeletedUser {userId: 'twice'})").run())
                .expectErrorSatisfies(error -> assertThat(error).hasStackTraceContaining("already exists"))
                .verify();
    }

    @Test
    @DisplayName("the friendship outbox gets one clock and one record per version")
    void start_shouldCreateTheFriendshipOutboxConstraints() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(neo4jClient.query(
                        "SHOW CONSTRAINTS YIELD name WHERE name IN ['friendship_clock_name', "
                                + "'friendship_outbox_version', 'migration_name'] RETURN count(*) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block()).isEqualTo(3));

        neo4jClient.query("CREATE (:FriendshipClock {name: 'friendship-events'})").run().block();
        StepVerifier.create(neo4jClient.query("CREATE (:FriendshipClock {name: 'friendship-events'})").run())
                .expectErrorSatisfies(error -> assertThat(error).hasStackTraceContaining("already exists"))
                .verify();
    }

    @Test
    @DisplayName("the purge's lookups by publication and parking date are indexed")
    void start_shouldCreateTheFriendshipOutboxPurgeIndexes() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(neo4jClient.query(
                        "SHOW INDEXES YIELD name WHERE name IN ['friendship_outbox_published_at', "
                                + "'friendship_outbox_parked_at'] RETURN count(*) AS c")
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block()).isEqualTo(2));
    }

    @Test
    @DisplayName("IF NOT EXISTS: running it again, as every restart does, succeeds and changes nothing")
    void start_shouldSucceed_whenTheConstraintAlreadyExists() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(constraints()).isEqualTo(1));

        StepVerifier.create(initializer.ensureSchema()).expectNextCount(1).verifyComplete();
        StepVerifier.create(initializer.ensureSchema()).expectNextCount(1).verifyComplete();

        assertThat(constraints()).isEqualTo(1);
        assertThat(initializer.isRunning()).isTrue();
    }
}
