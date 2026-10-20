package com.calendar.social.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemResponseWriterTest {

    private final ProblemResponseWriter writer = new ProblemResponseWriter(ServerCodecConfigurer.create());

    @Test
    void write_shouldAnswerTheProblemStatus_asProblemJson_withItsPropertiesAtTopLevel() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "down");
        problem.setProperty("code", "SCL-TEC-001");

        writer.write(exchange, problem).block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        // Like a problem returned from a controller: "code" beside "status", not under "properties".
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"SCL-TEC-001\"")
                .contains("\"status\":502")
                .doesNotContain("\"properties\"");
    }
}
