package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.services.AccountErasureService;
import com.calendar.social.domain.services.RelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/** Consumes wely-users' USER_* topics, retrying each record with backoff before logging and skipping it. */
@Slf4j
@Configuration
public class UserEventsConsumer {

    static final String USER_CREATED = "USER_CREATED";
    static final String USER_MODIFIED = "USER_MODIFIED";
    static final String USER_DELETED = "USER_DELETED";

    private final RelationshipService relationshipService;
    private final AccountErasureService accountErasureService;
    private final RecordReader reader;
    private final TechnicalErrorCounter errorCounter;
    private final long maxRetries;
    private final Retry retry;

    public UserEventsConsumer(
            RelationshipService relationshipService,
            AccountErasureService accountErasureService,
            RecordReader reader,
            TechnicalErrorCounter errorCounter,
            @Value("${app.consumers.retry.max-retries:5}") long maxRetries,
            @Value("${app.consumers.retry.first-backoff:1s}") Duration firstBackoff,
            @Value("${app.consumers.retry.max-backoff:30s}") Duration maxBackoff) {
        this.relationshipService = relationshipService;
        this.accountErasureService = accountErasureService;
        this.reader = reader;
        this.errorCounter = errorCounter;
        this.maxRetries = maxRetries;
        // The last failure itself, not Reactor's "retries exhausted" wrapper, reaches the log.
        this.retry = Retry.backoff(maxRetries, firstBackoff)
                .maxBackoff(maxBackoff)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> userCreated() {
        return flux -> flux.concatMap(record -> reader.read(record, UserCreatedEventDTO.class, USER_CREATED)
                        .flatMap(event -> apply(USER_CREATED, event.userId(),
                                () -> relationshipService.writeUser(event))))
                .then();
    }

    /** Sent by wely-users when a user edits their profile: the whole profile, applied as an upsert. */
    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> userModified() {
        return flux -> flux.concatMap(record -> reader.read(record, UserModifiedEventDTO.class, USER_MODIFIED)
                        .flatMap(event -> apply(USER_MODIFIED, event.userId(),
                                () -> relationshipService.updateUser(event))))
                .then();
    }

    /** Sent by wely-users when an account is deleted, at the user's request or as an orphan registration. */
    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> userDeleted() {
        return flux -> flux.concatMap(record -> reader.read(record, UserDeletedEventDTO.class, USER_DELETED)
                        .flatMap(event -> apply(USER_DELETED, event.userId(),
                                () -> accountErasureService.erase(event))))
                .then();
    }

    private Mono<Void> apply(String topic, String userId, Supplier<Mono<Void>> action) {
        if (userId == null || userId.isBlank()) {
            log.warn("{} record without userId, dropped", topic);
            return Mono.empty();
        }
        return Mono.defer(action)
                .retryWhen(retry)
                .doOnSuccess(unused -> log.debug("{} applied, userId={}", topic, userId))
                .onErrorResume(error -> {
                    logExhausted(topic, userId, error);
                    errorCounter.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    return Mono.empty();
                });
    }

    private void logExhausted(String topic, String userId, Throwable error) {
        if (USER_DELETED.equals(topic)) {
            // Not lost for good: the deletion stays on record in wely-users.
            log.warn("USER_DELETED not applied, userId={}, reconciliation will retry: {}", userId, error.getMessage());
        } else {
            log.error("{} dropped after {} retries, userId={}: {}", topic, maxRetries, userId, error.getMessage(), error);
        }
    }
}
