package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.infrastructure.messaging.adapters.UnpublishableRecordException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.messaging.converter.MessageConversionException;
import tools.jackson.core.JacksonException;

import java.util.List;

/**
 * Tells a failure that sending the same record again would repeat — a message that cannot be built
 * or serialized, one the broker refuses for its size, an invalid topic — from one that may pass
 * later, such as an unreachable broker or a timeout. Anything not known to be permanent is retriable.
 */
public final class PublicationErrorClassifier {

    /** The binder wraps the producer's error several times: deeper than this is a cause loop. */
    static final int MAX_CAUSE_DEPTH = 16;

    private static final List<Class<? extends Throwable>> PERMANENT = List.of(
            UnpublishableRecordException.class,
            SerializationException.class,
            JacksonException.class,
            MessageConversionException.class,
            RecordTooLargeException.class,
            RecordBatchTooLargeException.class,
            InvalidTopicException.class);

    private PublicationErrorClassifier() {
    }

    /** False when the error, or any of its causes, is known to fail the same way on every attempt. */
    public static boolean isRetriable(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable candidate = current;
            if (PERMANENT.stream().anyMatch(type -> type.isInstance(candidate))) {
                return false;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return true;
    }
}
