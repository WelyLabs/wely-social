package com.calendar.social.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Failures of a dependency rather than of the request.
 *
 * <p>502 rather than 500: the request was valid and this service is running — the graph
 * store is not, and 502 says exactly that.
 */
@Getter
@AllArgsConstructor
public enum TechnicalErrorCode {

    DATABASE_ERROR(
            "SCL-TEC-001",
            "Graph database unavailable",
            "The social graph could not be reached.",
            HttpStatus.BAD_GATEWAY),

    BROKER_UNAVAILABLE(
            "SCL-TEC-002",
            "Message broker unavailable",
            "The event could not be sent to Kafka.",
            HttpStatus.BAD_GATEWAY),

    /** Raised by the reconciliation in the background: no request waits on it, the next pass retries. */
    USERS_UNAVAILABLE(
            "SCL-TEC-003",
            "User service unavailable",
            "The deleted accounts could not be listed from wely-users.",
            HttpStatus.BAD_GATEWAY),

    MEDIA_UNAVAILABLE(
            "SCL-TEC-004",
            "Media service unavailable",
            "The community picture could not be stored or removed through wely-media.",
            HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
