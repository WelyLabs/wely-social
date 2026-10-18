package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.services.RelationshipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Drives the relay from a real Neo4j outbox to a mocked StreamBridge, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest(properties = {
        "app.friendship-outbox.relay.enabled=true",
        "app.friendship-outbox.relay.poll-interval=100ms"})
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class FriendshipOutboxRelayIntegrationTest {

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
    private StreamBridge streamBridge;

    @Autowired
    private RelationshipService service;

    @Autowired
    private RelationshipRepository relationships;

    @Autowired
    private ReactiveNeo4jClient neo4jClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetGraph() {
        reset(streamBridge);
        neo4jClient.query("MATCH (n) DETACH DELETE n").run().block();
        relationships.save(new UserCreatedEventDTO(ALICE, "alice", 1111, null)).block();
        relationships.save(new UserCreatedEventDTO(BOB, "bob", 2222, null)).block();
    }

    private long count(String cypher) {
        return neo4jClient.query(cypher).bindAll(Map.of())
                .fetchAs(Long.class).mappedBy((t, r) -> r.get("c").asLong()).one().block();
    }

    @SuppressWarnings("unchecked")
    private List<Message<byte[]>> sent() {
        ArgumentCaptor<Message<byte[]>> messages = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge, atLeast(1)).send(eq("friendship-out-0"), messages.capture());
        return messages.getAllValues();
    }

    @Test
    @DisplayName("committed changes reach Kafka in order, keyed by the pair, in the contract's shape, then are marked")
    void relay_shouldPublishCommittedChangesInOrder_andMarkThem() {
        when(streamBridge.send(eq("friendship-out-0"), any(Message.class))).thenReturn(true);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("MATCH (e:FriendshipOutboxEvent) WHERE e.publishedAt IS NOT NULL RETURN count(e) AS c") == 2);
        assertThat(count("MATCH (e:Unpublished) RETURN count(e) AS c")).isZero();

        List<Message<byte[]>> messages = sent();
        assertThat(messages).hasSize(2);
        JsonNode requested = objectMapper.readTree(messages.get(0).getPayload());
        JsonNode accepted = objectMapper.readTree(messages.get(1).getPayload());
        assertThat(requested.get("type").asString()).isEqualTo("REQUESTED");
        assertThat(requested.get("closeness").isNull()).isTrue();
        assertThat(accepted.get("type").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("fromUserId").asString()).isEqualTo(BOB);
        assertThat(accepted.get("fromUserName").asString()).isEqualTo("bob");
        assertThat(accepted.get("toUserId").asString()).isEqualTo(ALICE);
        assertThat(accepted.get("closeness").asString()).isEqualTo("FRIEND");
        assertThat(accepted.get("previousCloseness").isNull()).isTrue();
        assertThat(accepted.get("since").asString()).endsWith("Z");
        assertThat(accepted.get("occurredAt").asString()).endsWith("Z");
        assertThat(accepted.get("version").asLong()).isGreaterThan(requested.get("version").asLong());
        assertThat(messages).allSatisfy(message -> assertThat(new String(
                (byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo("alice-id:bob-id"));
    }

    @Test
    @DisplayName("a refused send stays pending with its attempt counted, and goes out on a later pass")
    void relay_shouldRetryARefusedRecord_untilItGoesOut() {
        when(streamBridge.send(eq("friendship-out-0"), any(Message.class))).thenReturn(false, false, true);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("MATCH (e:FriendshipOutboxEvent) WHERE e.publishedAt IS NOT NULL RETURN count(e) AS c") == 1);
        assertThat(count("MATCH (e:FriendshipOutboxEvent) RETURN e.attempts AS c")).isEqualTo(2);
    }
}
