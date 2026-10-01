package com.calendar.social.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class GlobalErrorHandlerTest {

    private WebTestClient client;

    @RestController
    static class TestController {

        @GetMapping("/business-error")
        Mono<Void> businessError() {
            return Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST));
        }

        @GetMapping("/conflict-error")
        Mono<Void> conflictError() {
            return Mono.error(new BusinessException(BusinessErrorCode.INVALID_USER_TAG));
        }

        @GetMapping("/technical-error")
        Mono<Void> technicalError() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR));
        }

        @GetMapping("/generic-error")
        Mono<Void> genericError() {
            return Mono.error(new IllegalStateException("connection string is postgres://user:hunter2@host"));
        }

        @GetMapping("/gone")
        Mono<Void> gone() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @GetMapping("/not-allowed")
        Mono<Void> notAllowed() {
            return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler())
                .build();
    }

    @Test
    @DisplayName("a business failure answers as application/problem+json")
    void handleBusinessException_shouldAnswerAsProblemDetail() {
        client.get().uri("/business-error")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("User not found")
                .jsonPath("$.detail").isEqualTo("No user matches the given handle.")
                .jsonPath("$.code").isEqualTo("SCL-BUS-005")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/scl-bus-005")
                .jsonPath("$.instance").isEqualTo("/business-error")
                .jsonPath("$.timestamp").exists();
    }

    @Test
    @DisplayName("the status comes from the error code, not from a default")
    void handleBusinessException_shouldUseTheStatusCarriedByTheCode() {
        client.get().uri("/conflict-error")
                .exchange()
                .expectStatus().isEqualTo(400)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SCL-BUS-006");
    }

    @Test
    @DisplayName("an unavailable dependency answers 502, not 500")
    void handleTechnicalException_shouldAnswerBadGateway() {
        // 500 would say "this service is broken". 502 says "this service works, what it
        // depends on does not" — which is the truth, and points the diagnosis somewhere.
        client.get().uri("/technical-error")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SCL-TEC-001")
                .jsonPath("$.title").isEqualTo("Graph database unavailable");
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SCL-TEC-000")
                .jsonPath("$.detail").isEqualTo("The request could not be completed.")
                // The exception message carried a connection string with a password:
                // none of that may cross the HTTP boundary.
                .jsonPath("$.detail").value(detail -> {
                    if (detail.toString().contains("hunter2") || detail.toString().contains("postgres")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }

    @Test
    @DisplayName("an unknown path keeps its 404 instead of becoming a 500")
    void handleResponseStatusException_shouldKeepTheOriginalStatus() {
        // The regression this guards: @ExceptionHandler(Exception.class) also catches
        // ResponseStatusException, so before SCL-REQ-000 existed every unknown path answered
        // 500. The service reported a fault of its own for a request it had handled correctly.
        client.get().uri("/gone")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.code").isEqualTo("SCL-REQ-000");
    }

    @Test
    @DisplayName("a rejected method keeps its 405")
    void handleResponseStatusException_shouldCarryAnyStatusThrough() {
        client.get().uri("/not-allowed")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody()
                .jsonPath("$.status").isEqualTo(405)
                .jsonPath("$.title").isEqualTo("Method Not Allowed")
                .jsonPath("$.code").isEqualTo("SCL-REQ-000");
    }

}
