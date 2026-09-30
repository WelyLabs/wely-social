package com.calendar.social.configuration;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.services.RelationshipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerConfigTest {

    @Mock
    private RelationshipService relationshipService;

    private KafkaConsumerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConsumerConfig(relationshipService);
    }

    private static Message<UserCreatedEventDTO> event(String userId) {
        return MessageBuilder
                .withPayload(new UserCreatedEventDTO(userId, "user-" + userId, 1234, null))
                .build();
    }

    @Test
    void userCreated_shouldWriteEachEventToTheGraph() {
        when(relationshipService.writeUser(any())).thenReturn(Mono.empty());

        Consumer<Flux<Message<UserCreatedEventDTO>>> consumer = config.userCreated();
        consumer.accept(Flux.just(event("u1"), event("u2")));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(relationshipService, times(2)).writeUser(any()));
    }

    @Test
    @DisplayName("a failing write is retried before being dropped")
    void userCreated_shouldRetryBeforeGivingUp() {
        AtomicInteger attempts = new AtomicInteger();
        when(relationshipService.writeUser(any()))
                .thenReturn(Mono.fromRunnable(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("Neo4j unavailable");
                }));

        config.userCreated().accept(Flux.just(event("u1")));

        // One initial attempt plus three bounded retries.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(attempts.get()).isEqualTo(4));
    }

    @Test
    @DisplayName("a permanently failing message does not cancel the stream: the next ones get through")
    void userCreated_shouldKeepConsumingAfterAPermanentFailure() {
        // This was the bug: the bare .subscribe() let the error cancel the subscription,
        // and the service silently stopped consuming until it restarted.
        when(relationshipService.writeUser(any()))
                .thenReturn(Mono.error(new IllegalStateException("boom")))
                .thenReturn(Mono.empty());

        config.userCreated().accept(Flux.just(event("fails"), event("succeeds")));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(relationshipService, times(2)).writeUser(any()));
    }

    @Test
    void userCreated_shouldCompleteQuietlyOnAnEmptyStream() {
        config.userCreated().accept(Flux.empty());

        verify(relationshipService, times(0)).writeUser(any());
    }
}
