package com.calendar.social.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names the API that springdoc generates from the controllers.
 *
 * <p>Without this the document is titled after the application and says nothing about what the
 * service is for. The operations themselves are derived from the handler signatures, so what is
 * worth writing by hand is the part no signature carries.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-social")
                .version("v1")
                .description("The friendship graph: requests, acceptance, rejection, and bidirectional relationship status. Consumes USER_CREATED."));
    }
}
