package com.calendar.social.exception;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The one place that counts technical failures in {@value GlobalErrorHandler#TECHNICAL_ERRORS_METRIC},
 * tagged by {@code code} and {@code source}: HTTP answers and background work alike.
 *
 * <p>A {@link BusinessException} is not counted: a refused request or record is an expected outcome,
 * not an incident. A {@link TechnicalException} counts under its code, anything else under
 * {@value GlobalErrorHandler#UNEXPECTED_FAILURE_CODE}.
 */
@Component
public class TechnicalErrorCounter {

    private final MeterRegistry meterRegistry;

    public TechnicalErrorCounter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** {@code source} is one of the {@code SOURCE_*} values of {@link GlobalErrorHandler}. */
    public void count(Throwable error, String source) {
        if (error instanceof BusinessException) {
            return;
        }
        String code = error instanceof TechnicalException technical
                ? technical.getErrorCode().getCode()
                : GlobalErrorHandler.UNEXPECTED_FAILURE_CODE;
        Counter.builder(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .description("Technical failures, by error code and by where they happened")
                .tag(GlobalErrorHandler.CODE_TAG, code)
                .tag(GlobalErrorHandler.SOURCE_TAG, source)
                .register(meterRegistry)
                .increment();
    }
}
