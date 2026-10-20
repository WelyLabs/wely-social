package com.calendar.social.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWebExceptionHandlerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProblemWebExceptionHandler handler = new ProblemWebExceptionHandler(
            new GlobalErrorHandler(meterRegistry), new ProblemResponseWriter(ServerCodecConfigurer.create()));

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/social-service/x"));
    }

    @Test
    void handle_shouldAnswerTheTechnicalProblem_andCountIt() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handle(exchange, new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"SCL-TEC-001\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"");
        assertThat(meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, "SCL-TEC-001").counter().count()).isEqualTo(1.0);
    }

    @Test
    void handle_shouldAnswerTheRequestProblem_forAStatusTheFrameworkDecided() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"" + GlobalErrorHandler.REQUEST_REFUSED_CODE + "\"");
    }

    @Test
    void handle_shouldAnswerTheUnexpectedProblem_forAnythingElse() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("password=hunter2")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"" + GlobalErrorHandler.UNEXPECTED_FAILURE_CODE + "\"")
                .doesNotContain("hunter2");
    }

    @Test
    void handle_shouldPassTheErrorOn_whenTheResponseIsAlreadyCommitted() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block(Duration.ofSeconds(10));
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);

        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorMatches(error -> error == failure)
                .verify(Duration.ofSeconds(10));

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }
}
