package com.calendar.social.exception;

import lombok.Getter;

/**
 * A dependency this service needs is unavailable: Neo4j, Kafka, wely-users or wely-media.
 *
 * <p>Distinct from {@link BusinessException} because the two call for different responses:
 * a business failure means the caller should change the request, a technical one means the
 * caller should retry and someone should look at an incident.
 *
 * <p>Raise it with the underlying failure as its cause whenever there is one, so the stack
 * trace that {@link GlobalErrorHandler} logs shows the driver, broker or HTTP error behind it.
 * The code-only constructor is for failures detected here, with no exception to carry.
 */
@Getter
public class TechnicalException extends RuntimeException {

    private final TechnicalErrorCode errorCode;

    public TechnicalException(TechnicalErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    public TechnicalException(TechnicalErrorCode errorCode, Throwable cause) {
        super(errorCode.getDetail(), cause);
        this.errorCode = errorCode;
    }
}
