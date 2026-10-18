package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.infrastructure.messaging.relay.PublicationErrorClassifier;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Sends outbox records through the real binder to a real broker and reads them back, when CI or
 * integration.tests is set. The unit test stops at StreamBridge; what only a broker shows is
 * whether the pair is the record's Kafka key (compaction keys on it) and whether REMOVED arrives
 * as a true tombstone, a null value, rather than an empty or "null" payload.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.stream.bindings.friendship-out-0.destination=" + KafkaFriendshipOutboxDispatcherIntegrationTest.CHANGED_TOPIC,
        "spring.cloud.stream.bindings.friendship-state-out-0.destination=" + KafkaFriendshipOutboxDispatcherIntegrationTest.STATE_TOPIC,
        // Small enough for the oversized record below, large enough for every real one.
        "spring.cloud.stream.kafka.binder.producer-properties.max.request.size=" + KafkaFriendshipOutboxDispatcherIntegrationTest.MAX_REQUEST_SIZE,
        "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=10000"})
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class KafkaFriendshipOutboxDispatcherIntegrationTest {

    static final String CHANGED_TOPIC = "FRIENDSHIP_CHANGED_IT";
    static final String STATE_TOPIC = "FRIENDSHIP_STATE_IT";
    static final int MAX_REQUEST_SIZE = 4096;

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    /** The binder reads its own brokers property, not the spring.kafka.bootstrap-servers @ServiceConnection sets. */
    @DynamicPropertySource
    static void bindTheBrokerToTheBinder(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    }

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    /** Created up front, the state topic compacted, as in the cluster: the binder may not create topics. */
    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(CHANGED_TOPIC, 1, (short) 1),
                    new NewTopic(STATE_TOPIC, 1, (short) 1).configs(Map.of(
                            TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT))))
                    .all().get();
        }
    }

    @Autowired
    private KafkaFriendshipOutboxDispatcher dispatcher;

    @Autowired
    private ObjectMapper objectMapper;

    private static FriendshipOutboxEventEntity record(long version, String type, String from, String to) {
        boolean friends = KafkaFriendshipOutboxDispatcher.FRIENDS_TYPES.contains(type);
        String pairKey = from.compareTo(to) < 0 ? from + ":" + to : to + ":" + from;
        return new FriendshipOutboxEventEntity(version, type, from, "someone", to, pairKey,
                friends ? "CLOSE" : null, null, friends ? ZonedDateTime.parse("2026-10-18T09:30:00Z") : null,
                ZonedDateTime.parse("2026-10-18T09:30:00.123Z"), null, 0L, null, null);
    }

    /** Polls the topic from its beginning until {@code expected} records with this key are read. */
    private List<ConsumerRecord<String, byte[]>> read(String topic, String key, int expected) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    @Test
    @DisplayName("an acceptance puts the friendship on the state topic under the a:b record key, a removal a null tombstone")
    void dispatch_shouldPublishTheStateUnderThePairKey_andATombstoneOnRemoval() {
        dispatcher.dispatch(record(100L, "ACCEPTED", "bob-id", "alice-id")).block();
        dispatcher.dispatch(record(101L, "REMOVED", "alice-id", "bob-id")).block();

        List<ConsumerRecord<String, byte[]>> states = read(STATE_TOPIC, "alice-id:bob-id", 2);
        assertThat(states).hasSize(2);
        JsonNode friends = objectMapper.readTree(states.get(0).value());
        assertThat(friends.get("a").asString()).isEqualTo("alice-id");
        assertThat(friends.get("b").asString()).isEqualTo("bob-id");
        assertThat(friends.get("friends").asBoolean()).isTrue();
        assertThat(friends.get("closeness").asString()).isEqualTo("CLOSE");
        assertThat(friends.get("since").asString()).isEqualTo("2026-10-18T09:30:00Z");
        assertThat(friends.get("version").asLong()).isEqualTo(100L);
        assertThat(states.get(1).value()).isNull();

        List<ConsumerRecord<String, byte[]>> changes = read(CHANGED_TOPIC, "alice-id:bob-id", 2);
        assertThat(changes).extracting(change -> objectMapper.readTree(change.value()).get("type").asString())
                .containsExactly("ACCEPTED", "REMOVED");
    }

    @Test
    @DisplayName("a request, a rejection, a withdrawal leave the state topic alone")
    void dispatch_shouldPublishNoState_forRecordsThatDoNotChangeTheFriendship() {
        dispatcher.dispatch(record(200L, "REQUESTED", "carol-id", "dave-id")).block();
        dispatcher.dispatch(record(201L, "REJECTED", "dave-id", "carol-id")).block();
        dispatcher.dispatch(record(202L, "CANCELLED", "carol-id", "dave-id")).block();
        // A marker: once it is read, anything the three above sent would have been read before it.
        dispatcher.dispatch(record(203L, "SNAPSHOT", "dave-id", "carol-id")).block();

        List<ConsumerRecord<String, byte[]>> states = read(STATE_TOPIC, "carol-id:dave-id", 1);
        assertThat(states).singleElement().satisfies(state ->
                assertThat(objectMapper.readTree(state.value()).get("version").asLong()).isEqualTo(203L));
        assertThat(read(CHANGED_TOPIC, "carol-id:dave-id", 4)).hasSize(4);
    }

    @Test
    @DisplayName("a record the broker refuses for its size fails with an error the relay parks instead of retrying")
    void dispatch_shouldFailPermanently_whenTheRecordIsTooLarge() {
        FriendshipOutboxEventEntity oversized = record(300L, "ACCEPTED", "erin-id", "frank-id");
        oversized.setFromUserName("x".repeat(MAX_REQUEST_SIZE * 2));

        Throwable error = catchThrowable(() -> dispatcher.dispatch(oversized).block());

        assertThat(error).hasRootCauseInstanceOf(RecordTooLargeException.class);
        assertThat(PublicationErrorClassifier.isRetriable(error)).isFalse();
    }
}
