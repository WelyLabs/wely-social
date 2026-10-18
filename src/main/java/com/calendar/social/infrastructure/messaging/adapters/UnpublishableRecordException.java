package com.calendar.social.infrastructure.messaging.adapters;

/** An outbox record whose Kafka messages could not be built: sending it again would fail the same way. */
public class UnpublishableRecordException extends RuntimeException {

    public UnpublishableRecordException(long version, Throwable cause) {
        super("FRIENDSHIP_CHANGED record " + version + " cannot be turned into Kafka messages", cause);
    }
}
