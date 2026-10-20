package com.calendar.social.infrastructure.messaging.adapters;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.test.StepVerifier;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RecordReaderTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TechnicalErrorCounter errorCounter = new TechnicalErrorCounter(meterRegistry);
    private final RecordReader reader = new RecordReader(JsonMapper.builder().build(), errorCounter);

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    @Test
    void read_shouldMapTheJsonPayloadToTheType() {
        StepVerifier.create(reader.read(record("""
                        {"userId":"u1","userName":"theo","hashtag":4271,"profilePicUrl":null}"""),
                        UserCreatedEventDTO.class, "T"))
                .expectNext(new UserCreatedEventDTO("u1", "theo", 4271, null))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void read_shouldReadAnIsoInstant_whenTheFieldIsAnInstant() {
        StepVerifier.create(reader.read(record("""
                        {"userId":"u1","deletedAt":"2026-10-15T14:03:27.123Z"}"""), UserDeletedEventDTO.class, "T"))
                .expectNext(new UserDeletedEventDTO("u1", Instant.parse("2026-10-15T14:03:27.123Z")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a field the producer added later is ignored, even by a mapper configured to refuse it")
    void read_shouldIgnoreUnknownFields_whateverTheMapperDefault() {
        RecordReader strict = new RecordReader(JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build(), errorCounter);

        StepVerifier.create(strict.read(record("""
                        {"userId":"u1","deletedAt":null,"reason":"USER_REQUEST"}"""), UserDeletedEventDTO.class, "T"))
                .expectNext(new UserDeletedEventDTO("u1", null))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an unreadable record is empty, not an error")
    void read_shouldBeEmpty_whenThePayloadIsNotJson() {
        StepVerifier.create(reader.read(record("{not json"), UserDeletedEventDTO.class, "T")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void read_shouldCountAnUnreadableRecord_asAnUnexpectedKafkaFailure() {
        StepVerifier.create(reader.read(record("{not json"), UserDeletedEventDTO.class, "T")).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .tag(GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_KAFKA)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void read_shouldNotCount_whenTheRecordIsRead() {
        StepVerifier.create(reader.read(record("{\"userId\":\"u1\"}"), UserDeletedEventDTO.class, "T"))
                .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }
}
