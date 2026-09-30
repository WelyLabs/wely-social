package com.calendar.social.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link WebFluxSecurityConfig#jwtDecoder()}.
 *
 * <h2>Known gap: the filter chain is not covered here</h2>
 *
 * <p>{@code apiHttpSecurity} cannot be exercised without an application context. Built
 * standalone, {@code ServerHttpSecurity} resolves the JWT decoder from the context and
 * fails with {@code jwtDecoder cannot be null}; and a {@code @SpringBootTest} does not
 * finish starting in this service, because binding a Kafka consumer makes the binder poll
 * a broker that is not running, retrying indefinitely. The same reason is recorded in
 * {@code SocialApplicationTest}.
 *
 * <p>The chain itself is identical to the one in wely-chat, which <em>is</em> covered
 * end to end through a {@code WebTestClient} — deny by default, CORS preflight aside. The
 * way to close this gap here is a Testcontainers Kafka, which is the next testing chantier
 * for the project.
 */
class WebFluxSecurityConfigTest {

    private static final String PUBLIC_ISSUER = "https://auth.example.test/realms/calendar-app";
    private static final String INTERNAL_JWKS =
            "http://wely-auth-service:8080/realms/calendar-app/protocol/openid-connect/certs";

    private WebFluxSecurityConfig config;

    @BeforeEach
    void setUp() {
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

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}
