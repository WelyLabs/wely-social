package com.calendar.social.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The error responses of the real application, not of the advice bound alone: which handler wins
 * when Spring's own ones are also in the context, and what a failure outside any controller answers.
 * Unit tests binding only {@link GlobalErrorHandler} cannot see either.
 *
 * <p>Boot's {@code ProblemDetailsExceptionHandler} is only registered with
 * {@code spring.webflux.problemdetails.enabled}, which this service leaves unset; these tests pin the
 * outcome whatever advices a later change brings in.
 */
// Same context as OpenApiConfigTest: no Spring Cloud Stream binder, which would wait on an absent broker.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.cloud.stream.config.BindingServiceConfiguration,"
                + "org.springframework.cloud.stream.function.FunctionConfiguration")
@ActiveProfiles("test")
class GlobalErrorHandlerWiringTest {

    private static final String TOKEN = "Bearer test-token";
    private static final String CLOSENESS = "/social-service/relationships/friend-1/closeness";

    @MockitoBean
    private StreamBridge streamBridge;

    // No Keycloak in a unit run: any bearer token decodes to the same caller.
    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("businessId", "caller-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build()));
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .defaultHeader(HttpHeaders.AUTHORIZATION, TOKEN)
                .build();
    }

    private WebTestClient.BodyContentSpec expectOurProblem(WebTestClient.ResponseSpec response, int status, String code) {
        return response
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    @DisplayName("an unknown path answers our 404 problem")
    void handle_shouldAnswerOurProblem_whenThePathIsUnknown() {
        expectOurProblem(client.get().uri("/social-service/no-such-path").exchange(),
                404, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("a wrong method answers our 405 problem, with the Allow header")
    void handle_shouldAnswerOurProblem_whenTheMethodIsNotAllowed() {
        WebTestClient.ResponseSpec response = client.delete().uri(CLOSENESS).exchange();

        response.expectHeader().valueEquals(HttpHeaders.ALLOW, "PUT");
        expectOurProblem(response, 405, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("an unsupported content type answers our 415 problem")
    void handle_shouldAnswerOurProblem_whenTheContentTypeIsUnsupported() {
        expectOurProblem(client.put().uri(CLOSENESS).contentType(MediaType.TEXT_PLAIN).bodyValue("CLOSE").exchange(),
                415, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("an unreadable body answers our 400 problem")
    void handle_shouldAnswerOurProblem_whenTheBodyIsUnreadable() {
        expectOurProblem(client.put().uri(CLOSENESS).contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange(), 400, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("an invalid field answers our 400 validation problem, with the field listed")
    void handle_shouldAnswerOurValidationProblem_whenAFieldIsInvalid() {
        expectOurProblem(client.put().uri(CLOSENESS).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange(),
                400, GlobalErrorHandler.VALIDATION_FAILED_CODE)
                .jsonPath("$.errors.closeness").exists();
    }

    @Test
    @DisplayName("the gateway's request id is the problem's, and comes back in the response header")
    void handle_shouldAnswerWithTheGatewayRequestId() {
        String id = "abcd1234-5678-90ef-abcd-1234567890ef";

        WebTestClient.ResponseSpec response = client.get().uri("/social-service/no-such-path")
                .header(RequestIds.REQUEST_ID_HEADER, id)
                .exchange();

        response.expectHeader().valueEquals(RequestIds.REQUEST_ID_HEADER, id);
        expectOurProblem(response, 404, GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").isEqualTo(id);
    }

    @Test
    @DisplayName("a request id that does not look like one is replaced, not echoed")
    void handle_shouldNotEchoAnInvalidRequestId() {
        expectOurProblem(client.get().uri("/social-service/no-such-path")
                        .header(RequestIds.REQUEST_ID_HEADER, "not an id!")
                        .exchange(), 404, GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").value(requestId -> assertThat(requestId).isNotEqualTo("not an id!"));
    }

    @Test
    @DisplayName("an unknown path answers 404 whatever the method, though Boot's static resource handler maps /**")
    void handle_shouldAnswer404_whenAnUnknownPathIsCalledWithAnotherMethod() {
        expectOurProblem(client.delete().uri("/social-service/no-such-path").exchange(),
                404, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    @Test
    @DisplayName("Boot's ProblemDetailsExceptionHandler stays out of the context: our advice is the only one answering")
    void context_shouldHoldNoOtherProblemDetailsAdvice() {
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class)).isEmpty();
    }

    @Test
    @DisplayName("a technical failure raised in a web filter answers our problem, and is counted")
    void handle_shouldAnswerOurProblem_whenAWebFilterFailsTechnically() {
        double before = technicalCount("SCL-TEC-001");

        expectOurProblem(client.get().uri(FailingFilterConfig.TECHNICAL_PATH).exchange(), 502, "SCL-TEC-001");

        assertThat(technicalCount("SCL-TEC-001")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a status raised in a web filter answers our request problem")
    void handle_shouldAnswerOurProblem_whenAWebFilterRefusesTheRequest() {
        expectOurProblem(client.get().uri(FailingFilterConfig.REFUSED_PATH).exchange(),
                413, GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }

    private double technicalCount(String code) {
        Counter counter = meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Fails before the security chain and before any handler: only a WebExceptionHandler sees it. */
    @TestConfiguration
    static class FailingFilterConfig {

        static final String TECHNICAL_PATH = "/test-only/filter-technical-failure";
        static final String REFUSED_PATH = "/test-only/filter-refusal";

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter failingFilter() {
            return (exchange, chain) -> switch (exchange.getRequest().getPath().value()) {
                case TECHNICAL_PATH -> Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR));
                case REFUSED_PATH -> Mono.error(new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE));
                default -> chain.filter(exchange);
            };
        }
    }
}
