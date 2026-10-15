package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.services.AccountErasureService;
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
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventsConsumerTest {

    private static final long MAX_RETRIES = 5;
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:03:27.123Z");

    @Mock
    private RelationshipService relationshipService;

    @Mock
    private AccountErasureService accountErasureService;

    private UserEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserEventsConsumer(relationshipService, accountErasureService,
                new RecordReader(JsonMapper.builder().build()), MAX_RETRIES, Duration.ofMillis(1), Duration.ofMillis(5));
    }

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Message<byte[]> created(String userId) {
        return record("""
                {"userId":"%s","userName":"user-%s","hashtag":1234,"profilePicUrl":null}""".formatted(userId, userId));
    }

    private static Message<byte[]> modified(String userId) {
        return record("""
                {"userId":"%s","userName":"camille_d","hashtag":4321,"firstName":"Cam","lastName":"Dupont",\
                "profilePicUrl":null}""".formatted(userId));
    }

    private static Message<byte[]> deleted(String userId) {
        return record("""
                {"userId":"%s","deletedAt":"2026-10-15T14:03:27.123Z"}""".formatted(userId));
    }

    /** A failing call that counts how many times it was subscribed, i.e. attempted. */
    private static Mono<Void> failing(AtomicInteger attempts) {
        return Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("Neo4j unavailable"));
        });
    }

    // --- userCreated ----------------------------------------------------------------------------

    @Test
    void userCreated_shouldWriteEachRecordToTheGraph() {
        when(relationshipService.writeUser(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userCreated().apply(Flux.just(created("u1"), created("u2")))).verifyComplete();

        verify(relationshipService).writeUser(new UserCreatedEventDTO("u1", "user-u1", 1234, null));
        verify(relationshipService).writeUser(new UserCreatedEventDTO("u2", "user-u2", 1234, null));
    }

    @Test
    @DisplayName("the function only assembles: nothing is consumed until the framework subscribes")
    void userCreated_shouldNotConsumeAnything_untilTheFrameworkSubscribes() {
        consumer.userCreated().apply(Flux.just(created("u1")));

        verifyNoInteractions(relationshipService);
    }

    @Test
    void userCreated_shouldRetryFiveTimesThenDrop_whenTheWriteKeepsFailing() {
        AtomicInteger attempts = new AtomicInteger();
        when(relationshipService.writeUser(any())).thenReturn(failing(attempts));

        StepVerifier.create(consumer.userCreated().apply(Flux.just(created("u1")))).verifyComplete();

        assertThat(attempts).hasValue((int) MAX_RETRIES + 1);
    }

    @Test
    @DisplayName("a permanently failing record does not end the stream: the next ones get through")
    void userCreated_shouldKeepConsuming_afterAPermanentFailure() {
        UserCreatedEventDTO failingEvent = new UserCreatedEventDTO("fails", "user-fails", 1234, null);
        UserCreatedEventDTO nextEvent = new UserCreatedEventDTO("succeeds", "user-succeeds", 1234, null);
        when(relationshipService.writeUser(failingEvent)).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(relationshipService.writeUser(nextEvent)).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userCreated().apply(Flux.just(created("fails"), created("succeeds"))))
                .verifyComplete();

        verify(relationshipService, times((int) MAX_RETRIES + 1)).writeUser(failingEvent);
        verify(relationshipService).writeUser(nextEvent);
    }

    @Test
    void userCreated_shouldSkipTheRecord_whenItIsNotJson() {
        when(relationshipService.writeUser(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userCreated().apply(Flux.just(record("{not json"), created("u2"))))
                .verifyComplete();

        verify(relationshipService, times(1)).writeUser(any());
    }

    @Test
    void userCreated_shouldDropTheRecord_whenItHasNoUserId() {
        StepVerifier.create(consumer.userCreated().apply(Flux.just(record("{\"userName\":\"x\",\"hashtag\":1}"))))
                .verifyComplete();

        verifyNoInteractions(relationshipService);
    }

    @Test
    void userCreated_shouldComplete_whenTheStreamIsEmpty() {
        StepVerifier.create(consumer.userCreated().apply(Flux.empty())).verifyComplete();

        verifyNoInteractions(relationshipService);
    }

    // --- userModified ---------------------------------------------------------------------------

    @Test
    void userModified_shouldApplyEachProfileToTheGraph() {
        when(relationshipService.updateUser(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userModified().apply(Flux.just(modified("u1")))).verifyComplete();

        verify(relationshipService).updateUser(new UserModifiedEventDTO("u1", "camille_d", 4321, "Cam", "Dupont", null));
    }

    @Test
    @DisplayName("a failing update does not end the stream: the next profiles still get through")
    void userModified_shouldKeepConsuming_afterAPermanentFailure() {
        UserModifiedEventDTO failingEvent = new UserModifiedEventDTO("u1", "camille_d", 4321, "Cam", "Dupont", null);
        UserModifiedEventDTO nextEvent = new UserModifiedEventDTO("u2", "camille_d", 4321, "Cam", "Dupont", null);
        AtomicInteger attempts = new AtomicInteger();
        when(relationshipService.updateUser(failingEvent)).thenReturn(failing(attempts));
        when(relationshipService.updateUser(nextEvent)).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userModified().apply(Flux.just(modified("u1"), modified("u2")))).verifyComplete();

        assertThat(attempts).hasValue((int) MAX_RETRIES + 1);
        verify(relationshipService).updateUser(nextEvent);
    }

    @Test
    void userModified_shouldDropTheRecord_whenItHasNoUserId() {
        StepVerifier.create(consumer.userModified().apply(Flux.just(record("{\"userId\":\" \"}")))).verifyComplete();

        verifyNoInteractions(relationshipService);
    }

    // --- userDeleted ----------------------------------------------------------------------------

    @Test
    void userDeleted_shouldEraseEachUser() {
        when(accountErasureService.erase(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(deleted("u1"), deleted("u2")))).verifyComplete();

        verify(accountErasureService, times(2)).erase(any());
        verifyNoInteractions(relationshipService);
    }

    @Test
    void userDeleted_shouldReadDeletedAtAsAnInstant_whenTheRecordCarriesIt() {
        when(accountErasureService.erase(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(deleted("u1")))).verifyComplete();

        verify(accountErasureService).erase(new UserDeletedEventDTO("u1", DELETED_AT));
    }

    @Test
    @DisplayName("today's {userId} records still erase: deletedAt is then null")
    void userDeleted_shouldPassANullDeletedAt_whenTheRecordComesFromAnOlderProducer() {
        when(accountErasureService.erase(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(record("{\"userId\":\"u1\"}")))).verifyComplete();

        verify(accountErasureService).erase(new UserDeletedEventDTO("u1", null));
    }

    @Test
    void userDeleted_shouldRetryFiveTimesThenContinue_whenErasureKeepsFailing() {
        UserDeletedEventDTO failingEvent = new UserDeletedEventDTO("fails", DELETED_AT);
        UserDeletedEventDTO nextEvent = new UserDeletedEventDTO("next", DELETED_AT);
        AtomicInteger attempts = new AtomicInteger();
        when(accountErasureService.erase(failingEvent)).thenReturn(failing(attempts));
        when(accountErasureService.erase(nextEvent)).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(deleted("fails"), deleted("next")))).verifyComplete();

        assertThat(attempts).hasValue((int) MAX_RETRIES + 1);
        verify(accountErasureService).erase(nextEvent);
    }

    @Test
    void userDeleted_shouldDropTheRecord_whenItHasNoUserId() {
        StepVerifier.create(consumer.userDeleted().apply(Flux.just(record("{\"deletedAt\":\"2026-10-15T14:03:27Z\"}"))))
                .verifyComplete();

        verify(accountErasureService, never()).erase(any());
    }

    @Test
    void userDeleted_shouldSkipTheRecord_whenItIsNotJson() {
        when(accountErasureService.erase(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(record("not json"), deleted("u2")))).verifyComplete();

        verify(accountErasureService).erase(new UserDeletedEventDTO("u2", DELETED_AT));
        verify(accountErasureService, times(1)).erase(any());
    }

    @Test
    @DisplayName("an exception thrown instead of signalled is retried like any failure, not fatal to the stream")
    void userDeleted_shouldKeepConsuming_whenTheErasureThrows() {
        UserDeletedEventDTO throwingEvent = new UserDeletedEventDTO("u1", DELETED_AT);
        UserDeletedEventDTO nextEvent = new UserDeletedEventDTO("u2", DELETED_AT);
        when(accountErasureService.erase(throwingEvent)).thenThrow(new IllegalStateException("thrown, not signalled"));
        when(accountErasureService.erase(nextEvent)).thenReturn(Mono.empty());

        StepVerifier.create(consumer.userDeleted().apply(Flux.just(deleted("u1"), deleted("u2")))).verifyComplete();

        verify(accountErasureService, times((int) MAX_RETRIES + 1)).erase(throwingEvent);
        verify(accountErasureService).erase(nextEvent);
    }
}
