package com.calendar.social.configuration;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.services.RelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Consumes {@code USER_CREATED} and materialises the corresponding node in the graph.
 *
 * <h2>Why every failure is handled here</h2>
 *
 * <p>Spring Cloud Stream hands a reactive consumer the whole {@link Flux} and steps
 * back: unlike an imperative {@code Consumer<T>}, the binder does not acknowledge per
 * message, does not retry, and offers no dead-letter queue. Whatever this method fails
 * to handle is lost, and an error reaching the subscriber cancels the subscription —
 * the service then silently stops consuming until it restarts.
 *
 * <p>So each message is retried with backoff, and a message that still fails is logged
 * with its user id and dropped rather than tearing down the stream. A dropped event
 * means a user exists in PostgreSQL with no node in the graph; the id in the log is
 * what makes that recoverable by hand.
 *
 * <p><b>Known limitation.</b> This is best-effort, not at-least-once. Proper delivery
 * guarantees would mean an imperative binding with {@code enableDlq}, which conflicts
 * with keeping the service non-blocking end to end, or an outbox on the producer side.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    private final RelationshipService relationshipService;

    public KafkaConsumerConfig(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @Bean
    public Consumer<Flux<Message<UserCreatedEventDTO>>> userCreated() {
        return flux -> flux
                .concatMap(this::writeUserNode)
                .subscribe(
                        unused -> { },
                        error -> log.error("USER_CREATED subscription terminated; "
                                + "no further events will be consumed until restart", error));
    }

    private Mono<Void> writeUserNode(Message<UserCreatedEventDTO> message) {
        UserCreatedEventDTO event = message.getPayload();

        return relationshipService.writeUser(event)
                .retryWhen(Retry.backoff(MAX_ATTEMPTS, FIRST_BACKOFF))
                .doOnSuccess(unused -> log.debug("USER_CREATED applied, userId={}", event.userId()))
                .onErrorResume(error -> {
                    log.error("USER_CREATED dropped after {} attempts, userId={}: {}",
                            MAX_ATTEMPTS, event.userId(), error.getMessage(), error);
                    return Mono.empty();
                });
    }
}
