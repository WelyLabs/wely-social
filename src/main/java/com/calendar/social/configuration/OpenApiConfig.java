package com.calendar.social.configuration;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Names the API that springdoc generates from the controllers, and documents its errors.
 *
 * <p>Without this the document is titled after the application and says nothing about what the
 * service is for. The operations themselves are derived from the handler signatures, so what is
 * worth writing by hand is the part no signature carries: the service's purpose, and the
 * {@code application/problem+json} shape every failure answers with.
 */
@Configuration
public class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_MEDIA_TYPE = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    static final String CLIENT_ERROR_RESPONSE = "4XX";
    static final String SERVER_ERROR_RESPONSE = "5XX";

    private static final String CODE_DESCRIPTION = """
            Stable identifier a client branches on. Families: BUS, a business rule refused the \
            request (change the request); VAL, invalid fields, listed in errors; REQ, the request was \
            refused by the framework (unknown path, wrong method, unreadable body); TEC, a dependency \
            failed (retry later; someone is looking at an incident).""";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-social")
                .version("v1")
                .description("The friendship graph: requests, acceptance, rejection, and bidirectional relationship status. Consumes USER_CREATED."));
    }

    /** Registers the {@code Problem} schema and declares 4XX/5XX problem responses on every operation. */
    @Bean
    public OpenApiCustomizer problemResponsesCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(OpenApiConfig::addProblemResponses));
        };
    }

    /** Every code this service can answer with, read from the enums and the handler: no list to keep in sync. */
    static List<String> errorCodes() {
        return Stream.of(
                        Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode),
                        Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode),
                        GlobalErrorHandler.HANDLER_CODES.stream())
                .flatMap(codes -> codes)
                .sorted()
                .toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> problemSchema() {
        StringSchema code = new StringSchema();
        code.setDescription(CODE_DESCRIPTION);
        code.setEnum(errorCodes());

        Schema problem = new ObjectSchema()
                .description("RFC 7807 problem detail, the body of every error response.")
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", code)
                .addProperty("timestamp", new DateTimeSchema()
                        .description("When the error was answered, ISO-8601."))
                .addProperty("requestId", new StringSchema()
                        .description("The request id, also in every log line about this failure."))
                .addProperty("errors", new MapSchema()
                        .additionalProperties(new StringSchema())
                        .description("Field name to message; only on a validation failure (VAL)."));
        return problem;
    }

    private static void addProblemResponses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        if (!responses.containsKey(CLIENT_ERROR_RESPONSE)) {
            responses.addApiResponse(CLIENT_ERROR_RESPONSE,
                    problemResponse("The request was refused: see code (BUS, VAL or REQ)."));
        }
        if (!responses.containsKey(SERVER_ERROR_RESPONSE)) {
            responses.addApiResponse(SERVER_ERROR_RESPONSE,
                    problemResponse("A dependency or the service failed: see code (TEC)."));
        }
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }
}
