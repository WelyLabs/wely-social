package com.calendar.social.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalErrorCodeTest {

    private static final Pattern CODE_FORMAT = Pattern.compile("^SCL-TEC-\\d{3}$");

    private static List<String> codes() {
        return Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheCodeFormat() {
        assertThat(codes()).allMatch(code -> CODE_FORMAT.matcher(code).matches());
    }

    @Test
    void values_shouldHaveUniqueCodes() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerA5xxStatus() {
        assertThat(TechnicalErrorCode.values())
                .allMatch(error -> error.getHttpStatus().is5xxServerError());
    }

    @Test
    void values_shouldNotReuseACodeTheHandlerWrites() {
        // SCL-TEC-000 belongs to the catch-all: an enum value reusing it would be counted and read as "unexpected".
        assertThat(codes()).doesNotContainAnyElementsOf(GlobalErrorHandler.HANDLER_CODES);
    }

    @Test
    void values_shouldNotOverlapBusinessCodes() {
        List<String> business = Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();

        assertThat(codes()).doesNotContainAnyElementsOf(business);
    }
}
