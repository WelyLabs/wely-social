package com.calendar.social.exception;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalErrorHandlerTest {

    private GlobalErrorHandler globalErrorHandler;

    @BeforeEach
    void setUp() {
        globalErrorHandler = new GlobalErrorHandler();
    }

    @Test
    void handleBusinessException_Success() {
        BusinessException ex = new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST);

        StepVerifier.create(globalErrorHandler.handleBusinessException(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(BusinessErrorCode.USER_DOES_NOT_EXIST.getMessage(), response.getBody().message());
                    assertEquals(BusinessErrorCode.USER_DOES_NOT_EXIST.getCode(), response.getBody().errorCode());
                })
                .verifyComplete();
    }

    @Test
    void handleTechnicalException_Success() {
        TechnicalException ex = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);

        StepVerifier.create(globalErrorHandler.handleTechnicalException(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(TechnicalErrorCode.DATABASE_ERROR.getMessage(), response.getBody().message());
                    assertEquals(TechnicalErrorCode.DATABASE_ERROR.getCode(), response.getBody().errorCode());
                })
                .verifyComplete();
    }

    @Test
    void handleValidationException_Success() {
        ValidationException ex = new ValidationException("Invalid data");

        StepVerifier.create(globalErrorHandler.handleValidationException(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("Invalid data", response.getBody().message());
                    assertEquals("VALIDATION_ERROR", response.getBody().errorCode());
                })
                .verifyComplete();
    }
}
