package com.calendar.social.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link OpenApiConfig#openApi} and the two things that make the documentation useful:
 * that it answers without a token, and that it describes the paths the gateway actually routes.
 *
 * <p>The specification is reachable in-cluster and in dev only. The gateway forwards
 * {@code /api/v1/<service>/**} and nothing else, so none of these paths is exposed publicly.
 */
// A running server, not bindToApplicationContext: springdoc registers its routes on the web
// handler, and the context-bound client does not see them — the first version of this test 404ed
// for that reason, which masked the real defect the last test here guards against.
// Spring Cloud Stream is kept out of this context on purpose. Emptying
// spring.cloud.function.definition is not enough: the binder also discovers the
// Consumer<Flux<Message<UserCreatedEvent>>> bean on its own, creates the userCreated-in-0
// binding, and KafkaTopicProvisioner then blocks forever on an AdminClient future waiting for
// a broker that is not there — the build hangs rather than failing. This test exercises HTTP,
// not messaging, so it does not need a binder at all.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.cloud.stream.config.BindingServiceConfiguration,"
                + "org.springframework.cloud.stream.function.FunctionConfiguration")
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired
    private ApplicationContext context;

    // FunctionConfiguration is excluded above, and it is what provides StreamBridge to the Kafka publisher.
    @MockitoBean
    private StreamBridge streamBridge;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("the generated document names the service rather than the application class")
    void openApi_shouldDescribeTheService() {
        var info = context.getBean(OpenAPI.class).getInfo();

        assertThat(info.getTitle()).isEqualTo("wely-social");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("the specification answers without a token")
    void apiDocs_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("wely-social");
    }

    @Test
    @DisplayName("the Swagger UI and its assets load without a token")
    void swaggerUi_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/webjars/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the documented paths carry the service prefix the gateway strips")
    void apiDocs_shouldDocumentPrefixedPaths() {
        // The regression this guards: WebConfig used to prefix every @RestController on the
        // classpath, springdoc's own included. That moved the specification itself to
        // /social-service/v3/api-docs behind authentication, and /v3/api-docs answered 404.
        byte[] body = client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"/social-service/");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the document describes the Problem body, with every error code of the service")
    void problemResponsesCustomizer_shouldRegisterTheProblemSchema() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.schemas.Problem.properties.requestId").exists()
                .jsonPath("$.components.schemas.Problem.properties.errors").exists()
                .jsonPath("$.components.schemas.Problem.properties.code.description").value(description ->
                        assertThat((String) description).contains("BUS", "VAL", "REQ", "TEC"))
                .jsonPath("$.components.schemas.Problem.properties.code.enum").value(codes ->
                        assertThat((List<Object>) codes).contains("SCL-BUS-001", "SCL-TEC-001", "SCL-VAL-001",
                                "SCL-REQ-000", "SCL-TEC-000"));
    }

    @Test
    @DisplayName("every documented operation answers its errors as application/problem+json")
    void problemResponsesCustomizer_shouldDeclareProblemResponsesOnOperations() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/social-service/relationships'].get.responses['4XX'].content['application/problem+json'].schema.$ref")
                .isEqualTo("#/components/schemas/Problem")
                .jsonPath("$.paths['/social-service/relationships'].get.responses['5XX'].content['application/problem+json'].schema.$ref")
                .isEqualTo("#/components/schemas/Problem");
    }

    @Test
    @DisplayName("a response an operation already declares is left as it is")
    void problemResponsesCustomizer_shouldKeepDeclaredResponses() {
        ApiResponse declared = new ApiResponse().description("declared by hand");
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("4XX", declared));
        OpenAPI document = new OpenAPI().paths(new Paths().addPathItem("/x", new PathItem().get(operation)));

        new OpenApiConfig().problemResponsesCustomizer().customise(document);

        assertThat(operation.getResponses().get("4XX")).isSameAs(declared);
        assertThat(operation.getResponses().get("5XX").getContent()).containsKey("application/problem+json");
        assertThat(document.getComponents().getSchemas()).containsKey("Problem");
    }

    @Test
    @DisplayName("the code enum is built from the enums and the handler, sorted and without duplicates")
    void errorCodes_shouldListEveryCodeOnce() {
        assertThat(OpenApiConfig.errorCodes())
                .doesNotHaveDuplicates()
                .isSorted()
                .contains("SCL-BUS-001", "SCL-TEC-001", "SCL-VAL-001", "SCL-REQ-000", "SCL-TEC-000");
    }
}
