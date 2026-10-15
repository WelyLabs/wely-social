package com.calendar.social.infrastructure.messaging.adapters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Reads a Kafka record's JSON bytes, so an unreadable record is skipped instead of ending the stream. */
@Slf4j
@Component
public class RecordReader {

    private final ObjectMapper objectMapper;

    public RecordReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The record as {@code type}, ignoring unknown fields; empty, and logged without content, when unreadable. */
    public <T> Mono<T> read(Message<byte[]> record, Class<T> type, String topic) {
        return Mono.fromCallable(() -> objectMapper.readerFor(type)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .<T>readValue(record.getPayload()))
                .onErrorResume(error -> {
                    // The exception type only: a parser message can quote the record's values.
                    log.error("{} record unreadable, dropped ({})", topic, error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
