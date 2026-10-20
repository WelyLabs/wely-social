package com.calendar.social.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalExceptionTest {

    @Test
    void constructor_shouldCarryTheCodeAndItsDetail_withoutACause() {
        TechnicalException exception = new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE);

        assertThat(exception.getErrorCode()).isEqualTo(TechnicalErrorCode.BROKER_UNAVAILABLE);
        assertThat(exception).hasMessage(TechnicalErrorCode.BROKER_UNAVAILABLE.getDetail());
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void constructor_shouldKeepTheUnderlyingFailureAsCause_whenOneIsGiven() {
        IllegalStateException driverFailure = new IllegalStateException("Bolt connection closed");

        TechnicalException exception = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR, driverFailure);

        assertThat(exception.getErrorCode()).isEqualTo(TechnicalErrorCode.DATABASE_ERROR);
        // The public detail, never the driver's message: the latter may name hosts or queries.
        assertThat(exception).hasMessage(TechnicalErrorCode.DATABASE_ERROR.getDetail());
        assertThat(exception.getCause()).isSameAs(driverFailure);
    }
}
