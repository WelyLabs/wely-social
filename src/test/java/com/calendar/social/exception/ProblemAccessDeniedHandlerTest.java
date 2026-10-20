package com.calendar.social.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAccessDeniedHandlerTest {

    private final ProblemAccessDeniedHandler handler = new ProblemAccessDeniedHandler(
            new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())), new ProblemResponseWriter(ServerCodecConfigurer.create()));

    @Test
    void handle_shouldAnswerOur403Problem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/social-service/x"));

        handler.handle(exchange, new AccessDeniedException("denied")).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"SCL-REQ-002\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"");
    }
}
