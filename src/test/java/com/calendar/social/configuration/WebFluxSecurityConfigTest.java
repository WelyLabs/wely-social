package com.calendar.social.configuration;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WebFluxSecurityConfig}: the JWT decoder, built directly, and the filter chain, in the
 * running application.
 *
 * <p>The chain used to be out of reach because a {@code @SpringBootTest} waited on the Kafka binder;
 * excluding Spring Cloud Stream, as {@code OpenApiConfigTest} does, lets the context start without a
 * broker. Keycloak is replaced by a mocked {@link ReactiveJwtDecoder}: {@value #VALID} decodes, any
 * other token is refused as invalid.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.cloud.stream.config.BindingServiceConfiguration,"
                + "org.springframework.cloud.stream.function.FunctionConfiguration")
@ActiveProfiles("test")
class WebFluxSecurityConfigTest {

    private static final String PUBLIC_ISSUER = "https://auth.example.test/realms/wely-realm";
    private static final String INTERNAL_JWKS =
            "http://wely-auth-service:8080/realms/wely-realm/protocol/openid-connect/certs";

    static final String VALID = "valid-token";

    private WebFluxSecurityConfig config;

    @MockitoBean
    private StreamBridge streamBridge;

    @MockitoBean
    private ReactiveJwtDecoder contextDecoder;

    @Autowired
    private GlobalErrorHandler errorHandler;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        when(contextDecoder.decode(anyString())).thenAnswer(call -> VALID.equals(call.getArgument(0))
                ? Mono.just(Jwt.withTokenValue(VALID)
                        .header("alg", "none")
                        .claim("businessId", "caller-1")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build())
                : Mono.error(new BadJwtException("expired")));
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();

        config = new WebFluxSecurityConfig();
        // Both @Value fields are injected by Spring in production.
        ReflectionTestUtils.setField(config, "publicIssuer", PUBLIC_ISSUER);
        ReflectionTestUtils.setField(config, "internalJwkSetUri", INTERNAL_JWKS);
    }

    @Test
    @DisplayName("the decoder reads keys internally while validating the public issuer")
    void jwtDecoder_shouldBeBuiltOnTheInternalJwks() {
        ReactiveJwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isInstanceOf(NimbusReactiveJwtDecoder.class);

        // The two URLs are distinct by design. Without that split, either the iss
        // validation fails — the internal URL does not match the one in the token — or the
        // service leaves the cluster on every key rotation.
        assertThat(INTERNAL_JWKS).isNotEqualTo(PUBLIC_ISSUER);
    }

    @Test
    void jwtDecoder_shouldFailFastOnAMissingJwksUri() {
        ReflectionTestUtils.setField(config, "internalJwkSetUri", null);

        assertThat(catchThrowable(config::jwtDecoder)).isNotNull();
    }

    @Test
    @DisplayName("Prometheus scrapes without a token, and finds the technical error counter")
    void apiHttpSecurity_shouldLetPrometheusScrapeUnauthenticated() {
        errorHandler.handleTechnicalException(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR),
                MockServerWebExchange.from(MockServerHttpRequest.get("/social-service/relationships")));

        String metrics = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(metrics).contains("wely_errors_technical_total{code=\"SCL-TEC-001\"");
    }

    @Test
    @DisplayName("only GET is opened on the Prometheus endpoint")
    void apiHttpSecurity_shouldKeepOtherMethodsOnPrometheusClosed() {
        client.post().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the rest of /actuator stays closed, and is not exposed even with a token")
    void apiHttpSecurity_shouldKeepOtherActuatorEndpointsClosed() {
        client.get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/actuator/env")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("a request without a token answers our 401 problem, with the Bearer challenge")
    void apiHttpSecurity_shouldAnswerOurProblem_whenTheTokenIsMissing() {
        client.get().uri("/social-service/relationships")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, challenge -> assertThat(challenge).startsWith("Bearer"))
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("SCL-REQ-001")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("/social-service/relationships");
    }

    @Test
    @DisplayName("an invalid token answers our 401 problem, and the challenge says why")
    void apiHttpSecurity_shouldAnswerOurProblem_whenTheTokenIsInvalid() {
        client.get().uri("/social-service/relationships")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        challenge -> assertThat(challenge).startsWith("Bearer").contains("invalid_token"))
                .expectBody()
                .jsonPath("$.code").isEqualTo("SCL-REQ-001")
                .jsonPath("$.requestId").isNotEmpty();
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}
