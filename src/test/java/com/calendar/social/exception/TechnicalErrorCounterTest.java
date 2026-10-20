package com.calendar.social.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalErrorCounterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TechnicalErrorCounter counter = new TechnicalErrorCounter(meterRegistry);

    private double count(String code, String source) {
        return meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code)
                .tag(GlobalErrorHandler.SOURCE_TAG, source)
                .counter().count();
    }

    @Test
    void count_shouldCountATechnicalFailure_underItsCodeAndSource() {
        counter.count(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE), GlobalErrorHandler.SOURCE_OUTBOX);
        counter.count(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE), GlobalErrorHandler.SOURCE_OUTBOX);

        assertThat(count("SCL-TEC-002", GlobalErrorHandler.SOURCE_OUTBOX)).isEqualTo(2.0);
    }

    @Test
    void count_shouldKeepSourcesApart() {
        counter.count(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR), GlobalErrorHandler.SOURCE_HTTP);
        counter.count(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR), GlobalErrorHandler.SOURCE_KAFKA);

        assertThat(count("SCL-TEC-001", GlobalErrorHandler.SOURCE_HTTP)).isEqualTo(1.0);
        assertThat(count("SCL-TEC-001", GlobalErrorHandler.SOURCE_KAFKA)).isEqualTo(1.0);
    }

    @Test
    void count_shouldCountAnyOtherFailure_asUnexpected() {
        counter.count(new IllegalStateException("boom"), GlobalErrorHandler.SOURCE_JOB);

        assertThat(count(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, GlobalErrorHandler.SOURCE_JOB)).isEqualTo(1.0);
    }

    @Test
    void count_shouldNotCountABusinessFailure() {
        counter.count(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST), GlobalErrorHandler.SOURCE_KAFKA);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }
}
