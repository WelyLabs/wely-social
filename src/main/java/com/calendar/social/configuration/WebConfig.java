package com.calendar.social.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Adds the service prefix to this service's controllers.
 *
 * <p>Deliberately without {@code @EnableWebFlux}: that annotation switches Boot's WebFlux
 * auto-configuration off as a whole, and every {@code spring.webflux.*} property with it (the
 * multipart limit was silently 256 KB instead of 11 MB). Boot still applies this configurer.
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        // Scoped to this service's own controllers by package, not by the @RestController
        // annotation: that predicate also matched springdoc's OpenApiWebfluxResource, which
        // moved the specification to /social-service/v3/api-docs and left /v3/api-docs a 404.
        configurer.addPathPrefix(
                "/social-service",
                HandlerTypePredicate.forBasePackage("com.calendar.social.application.rest"));
    }
}