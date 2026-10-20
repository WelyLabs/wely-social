package com.calendar.social.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAuthenticationEntryPointTest {

    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(
            new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())), new ProblemResponseWriter(ServerCodecConfigurer.create()));

    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/social-service/relationships"));

    @Test
    void commence_shouldAnswerOur401Problem_andKeepTheBearerChallenge() {
        entryPoint.commence(exchange, new AuthenticationCredentialsNotFoundException("no token"))
                .block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"SCL-REQ-001\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"")
                .contains("\"instance\":\"/social-service/relationships\"");
    }

    @Test
    void commence_shouldSayWhyTheTokenWasRefused_inTheChallenge() {
        entryPoint.commence(exchange, new InvalidBearerTokenException("expired"))
                .block(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .contains("error=\"invalid_token\"");
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(10)))
                .contains("\"code\":\"SCL-REQ-001\"");
    }
}
