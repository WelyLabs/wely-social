package com.calendar.social.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessErrorCodeTest {

    private static final Pattern CODE_FORMAT = Pattern.compile("^SCL-(BUS|VAL|REQ)-\\d{3}$");

    private static List<String> codes() {
        return Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();
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
    void values_shouldAnswerA4xxStatus() {
        assertThat(BusinessErrorCode.values())
                .allMatch(error -> error.getHttpStatus().is4xxClientError());
    }

    @Test
    void values_shouldNotReuseACodeTheHandlerWrites() {
        assertThat(codes()).doesNotContainAnyElementsOf(GlobalErrorHandler.HANDLER_CODES);
    }

    @Test
    void values_shouldNotOverlapTechnicalCodes() {
        List<String> technical = Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();

        assertThat(codes()).doesNotContainAnyElementsOf(technical);
    }
}
