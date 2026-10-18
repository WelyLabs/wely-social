package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.services.RelationshipService;
import org.apache.kafka.common.errors.RecordTooLargeException;
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
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.support.MessageBuilder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Drives the relay from a real Neo4j outbox to a mocked StreamBridge, when CI or integration.tests is set. */
@Testcontainers
@SpringBootTest(properties = {
        "app.friendship-outbox.relay.enabled=true",
        "app.friendship-outbox.relay.poll-interval=100ms",
        "app.friendship-outbox.relay.max-backoff=200ms",
        "app.friendship-outbox.relay.max-attempts=" + FriendshipOutboxRelayIntegrationTest.MAX_ATTEMPTS})
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

    static final int MAX_ATTEMPTS = 4;
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

    @SuppressWarnings("unchecked")
    private List<Message<?>> sentStates() {
        ArgumentCaptor<Message<?>> messages = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge, atLeast(1)).send(eq("friendship-state-out-0"), messages.capture());
        return messages.getAllValues();
    }

    private static String key(Message<?> message) {
        return new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8);
    }

    private long published() {
        return count("MATCH (e:FriendshipOutboxEvent) WHERE e.publishedAt IS NOT NULL RETURN count(e) AS c");
    }

    @Test
    @DisplayName("committed changes reach Kafka in order, keyed by the pair, in the contract's shape, then are marked")
    void relay_shouldPublishCommittedChangesInOrder_andMarkThem() {
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

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
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(false, false, true);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("MATCH (e:FriendshipOutboxEvent) WHERE e.publishedAt IS NOT NULL RETURN count(e) AS c") == 1);
        assertThat(count("MATCH (e:FriendshipOutboxEvent) RETURN e.attempts AS c")).isEqualTo(2);
    }

    @Test
    @DisplayName("an acceptance also sends the pair's state, a removal its tombstone, both keyed a:b; a request none")
    void relay_shouldSendTheStateRecord_forChangesToTheFriendship() {
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();
        await().atMost(Duration.ofSeconds(10)).until(() -> published() == 2);
        service.deleteFriendship(BOB, ALICE).block();
        await().atMost(Duration.ofSeconds(10)).until(() -> published() == 3);

        List<Message<?>> states = sentStates();
        assertThat(states).hasSize(2);
        assertThat(states).allSatisfy(message -> assertThat(key(message)).isEqualTo("alice-id:bob-id"));
        JsonNode friends = objectMapper.readTree((byte[]) states.get(0).getPayload());
        assertThat(friends.get("a").asString()).isEqualTo(ALICE);
        assertThat(friends.get("b").asString()).isEqualTo(BOB);
        assertThat(friends.get("friends").asBoolean()).isTrue();
        assertThat(friends.get("closeness").asString()).isEqualTo("FRIEND");
        assertThat(friends.get("since").asString()).endsWith("Z");
        JsonNode accepted = objectMapper.readTree(sent().get(1).getPayload());
        assertThat(friends.get("version").asLong()).isEqualTo(accepted.get("version").asLong());
        assertThat(states.get(1).getPayload()).isSameAs(KafkaNull.INSTANCE);
    }

    @Test
    @DisplayName("a record Kafka refuses for good is parked, and the records after it still go out")
    void relay_shouldParkAPermanentlyRefusedRecord_andPublishTheNextOnes() {
        when(streamBridge.send(anyString(), any(Message.class)))
                .thenThrow(new MessageHandlingException(MessageBuilder.withPayload("x").build(), "send failed",
                        new RecordTooLargeException("too large")))
                .thenReturn(true);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();
        service.acceptFriendRequest(BOB, ALICE).block();

        await().atMost(Duration.ofSeconds(10)).until(() -> published() == 1);
        assertThat(count("MATCH (e:FriendshipOutboxEvent:Parked {type: 'REQUESTED'}) "
                + "WHERE e.parkedAt IS NOT NULL AND e.lastError CONTAINS 'too large' RETURN count(e) AS c")).isEqualTo(1);
        assertThat(count("MATCH (e:FriendshipOutboxEvent {type: 'ACCEPTED'}) WHERE e.publishedAt IS NOT NULL "
                + "RETURN count(e) AS c")).isEqualTo(1);
        assertThat(count("MATCH (e:Unpublished) RETURN count(e) AS c")).isZero();
    }

    @Test
    @DisplayName("a record failing max-attempts times in a row is parked even though the error looked transient")
    void relay_shouldParkARecord_afterTheMaximumAttempts() {
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(false);

        service.sendFriendRequest(ALICE, "bob#2222", null).block();

        await().atMost(Duration.ofSeconds(20)).until(() ->
                count("MATCH (e:FriendshipOutboxEvent:Parked) RETURN count(e) AS c") == 1);
        assertThat(count("MATCH (e:FriendshipOutboxEvent) RETURN e.attempts AS c")).isEqualTo(MAX_ATTEMPTS);
        assertThat(published()).isZero();
    }
}
