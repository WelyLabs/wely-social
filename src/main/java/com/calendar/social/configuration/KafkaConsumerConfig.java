package com.calendar.social.configuration;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
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
import java.util.function.Function;

/**
 * Consumes {@code USER_CREATED}, {@code USER_MODIFIED} and {@code USER_DELETED}: creates, updates or
 * removes the user's node.
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
        return flux -> consume("USER_CREATED", flux, UserCreatedEventDTO::userId, relationshipService::writeUser);
    }

    /** Sent by wely-users when a user edits their profile: the whole profile, applied as an upsert. */
    @Bean
    public Consumer<Flux<Message<UserModifiedEventDTO>>> userModified() {
        return flux -> consume("USER_MODIFIED", flux, UserModifiedEventDTO::userId, relationshipService::updateUser);
    }

    /** Sent by wely-users when it removes a user left by a cancelled registration. */
    @Bean
    public Consumer<Flux<Message<UserDeletedEventDTO>>> userDeleted() {
        return flux -> consume("USER_DELETED", flux, UserDeletedEventDTO::userId, relationshipService::removeUser);
    }

    private <T> void consume(String type, Flux<Message<T>> flux,
                             Function<T, String> userId, Function<T, Mono<Void>> apply) {
        flux.concatMap(message -> {
                    T event = message.getPayload();
                    return apply.apply(event)
                            .retryWhen(Retry.backoff(MAX_ATTEMPTS, FIRST_BACKOFF))
                            .doOnSuccess(unused -> log.debug("{} applied, userId={}", type, userId.apply(event)))
                            .onErrorResume(error -> {
                                log.error("{} dropped after {} attempts, userId={}: {}",
                                        type, MAX_ATTEMPTS, userId.apply(event), error.getMessage(), error);
                                return Mono.empty();
                            });
                })
                .subscribe(
                        unused -> { },
                        error -> log.error("{} subscription terminated; "
                                + "no further events will be consumed until restart", type, error));
    }
}
