package com.calendar.social.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorHandlerTest {

    private WebTestClient client;
    private SimpleMeterRegistry meterRegistry;

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

        @GetMapping("/method-not-allowed")
        Mono<Void> methodNotAllowed() {
            return Mono.error(new MethodNotAllowedException(HttpMethod.GET, Set.of(HttpMethod.PUT)));
        }

        @PostMapping("/validated")
        Mono<Void> validated(@Valid @RequestBody Named body) {
            return Mono.empty();
        }
    }

    record Named(@NotBlank String name) {
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler(meterRegistry))
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
                .jsonPath("$.detail").isEqualTo("No user matches the given handle or id.")
                .jsonPath("$.code").isEqualTo("SCL-BUS-005")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/scl-bus-005")
                .jsonPath("$.instance").isEqualTo("/business-error")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("a business failure is not counted as a technical one")
    void handleBusinessException_shouldNotCountATechnicalFailure() {
        client.get().uri("/business-error").exchange().expectStatus().isNotFound();

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
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
                .jsonPath("$.title").isEqualTo("Graph database unavailable")
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("a technical failure is counted under its code")
    void handleTechnicalException_shouldCountTheFailureByCode() {
        client.get().uri("/technical-error").exchange().expectStatus().isEqualTo(502);
        client.get().uri("/technical-error").exchange().expectStatus().isEqualTo(502);

        assertThat(technicalCount("SCL-TEC-001")).isEqualTo(2.0);
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE).counter()).isNull();
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank)
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
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
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
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("an unexpected error is counted as a technical failure under SCL-TEC-000")
    void handleUnexpectedException_shouldCountTheFailure() {
        client.get().uri("/generic-error").exchange().expectStatus().is5xxServerError();

        assertThat(technicalCount(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a validation failure lists its fields and carries the request id")
    void handleValidationException_shouldListTheFieldsAndCarryTheRequestId() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
                .jsonPath("$.errors.name").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    @DisplayName("a 405 keeps the Allow header the status calls for")
    void handleResponseStatusException_shouldKeepTheStatusHeaders() {
        client.get().uri("/method-not-allowed")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals(HttpHeaders.ALLOW, "PUT")
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("handle maps each failure as its own handler would")
    void handle_shouldMapEachFailureAsItsHandler() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        GlobalErrorHandler handler = new GlobalErrorHandler(meterRegistry);

        assertThat(handler.handle(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST), exchange)
                .getProperties()).containsEntry(GlobalErrorHandler.CODE_TAG, "SCL-BUS-005");
        assertThat(handler.handle(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE), exchange)
                .getStatus()).isEqualTo(502);
        assertThat(handler.handle(new ResponseStatusException(HttpStatus.NOT_FOUND), exchange)
                .getProperties()).containsEntry(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        assertThat(handler.handle(new AssertionError("not even an Exception"), exchange)
                .getProperties())
                .containsEntry(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .containsEntry(GlobalErrorHandler.REQUEST_ID_PROPERTY, exchange.getRequest().getId());

        assertThat(technicalCount("SCL-TEC-002")).isEqualTo(1.0);
        assertThat(technicalCount(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    private double technicalCount(String code) {
        return meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code)
                .counter()
                .count();
    }

    private static void assertNotBlank(Object value) {
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value).isNotBlank();
    }

}
