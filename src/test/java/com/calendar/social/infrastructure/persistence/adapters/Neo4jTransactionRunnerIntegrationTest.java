package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves on a real Neo4j that {@link Neo4jTransactionRunner} commits or rolls back several adapters together. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class Neo4jTransactionRunnerIntegrationTest {

    @Container
    @ServiceConnection
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26")
            .withAdminPassword("test-password");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String ALICE = "alice-id";
    private static final Instant AT = Instant.parse("2026-10-15T14:03:27Z");

    @Autowired
    private Neo4jTransactionRunner runner;

    @Autowired
    private Neo4jDeletedUserMarkersAdapter markers;

    @Autowired
    private Neo4jRelationshipRepositoryAdapter relationships;

    @Autowired
    private Neo4jCommunityRepositoryAdapter communities;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        relationships.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null)).block();
        communities.create(ALICE, new CommunityDraft("Club", null, List.of(), CommunityVisibility.PUBLIC), AT).block();
        communities.removeMember(communities.findJoined(ALICE).blockFirst().communityId(), ALICE).block();
    }

    private long count(String cypher) {
        return neo4jClient.query(cypher)
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    @Test
    void inTransaction_shouldCommitEveryWrite_whenAllStepsSucceed() {
        StepVerifier.create(runner.inTransaction(markers.markDeleted(ALICE, AT)
                        .then(Mono.defer(() -> relationships.delete(ALICE)))
                        .thenMany(Flux.defer(() -> communities.deleteEmptyCommunitiesCreatedBy(ALICE))).count()))
                .expectNext(1L)
                .verifyComplete();

        assertThat(count("MATCH (d:DeletedUser) RETURN count(d) AS c")).isEqualTo(1);
        assertThat(count("MATCH (u:User) RETURN count(u) AS c")).isZero();
        assertThat(count("MATCH (c:Community) RETURN count(c) AS c")).isZero();
    }

    @Test
    @DisplayName("a failure after the marker and the delete rolls both back: a later attempt starts clean")
    void inTransaction_shouldRollBackTheMarkerAndTheDelete_whenALaterStepFails() {
        // As an adapter fails: already translated, so it comes out of the runner untouched.
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);

        StepVerifier.create(runner.inTransaction(markers.markDeleted(ALICE, AT)
                        .then(Mono.defer(() -> relationships.delete(ALICE)))
                        .thenMany(Flux.defer(() -> communities.deleteEmptyCommunitiesCreatedBy(ALICE)))
                        .then(Mono.error(failure))))
                .verifyErrorSatisfies(error -> assertThat(error).isSameAs(failure));

        assertThat(count("MATCH (d:DeletedUser) RETURN count(d) AS c")).isZero();
        assertThat(count("MATCH (u:User {userId: 'alice-id'}) RETURN count(u) AS c")).isEqualTo(1);
        assertThat(count("MATCH (c:Community) RETURN count(c) AS c")).isEqualTo(1);
    }
}
