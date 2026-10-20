package com.calendar.social.configuration;

import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.calendar.social.application.rest.RelationshipController;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link WebConfig}: the path prefix, directly, and in the running application that Boot's
 * WebFlux auto-configuration is in force (no {@code @EnableWebFlux}), so {@code spring.webflux.*}
 * properties apply.
 */
// Same context as OpenApiConfigTest: no Spring Cloud Stream binder, which would wait on an absent broker.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.cloud.stream.config.BindingServiceConfiguration,"
                + "org.springframework.cloud.stream.function.FunctionConfiguration")
@ActiveProfiles("test")
class WebConfigTest {

    /** spring.webflux.multipart.max-in-memory-size=11MB in application.properties. */
    private static final long MULTIPART_LIMIT = 11L * 1024 * 1024;

    private final WebConfig config = new WebConfig();

    @MockitoBean
    private StreamBridge streamBridge;

    @Autowired
    private ServerCodecConfigurer codecs;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("spring.webflux.multipart.max-in-memory-size reaches the multipart reader")
    void webFluxAutoConfiguration_shouldApplyTheMultipartLimit() {
        // With @EnableWebFlux this was the framework default, 256 KB, whatever the property said.
        assertThat(codecs.getReaders()).filteredOn(DefaultPartHttpMessageReader.class::isInstance)
                .singleElement()
                .extracting(reader -> ((DefaultPartHttpMessageReader) reader).getMaxInMemorySize())
                .isEqualTo((int) MULTIPART_LIMIT);
    }

    @Test
    @DisplayName("Boot's WebFlux auto-configuration is in force, and still applies this configurer's prefix")
    void webFluxAutoConfiguration_shouldBeActive_andKeepThePrefix() {
        assertThat(context.getBeanNamesForType(WebFluxAutoConfiguration.class)).isNotEmpty();
        assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                .anySatisfy(mapping -> assertThat(mapping.getPatternsCondition().getPatterns())
                        .anySatisfy(pattern -> assertThat(pattern.getPatternString()).startsWith("/social-service/")));
    }

    @Test
    @DisplayName("controllers are prefixed with the service segment")
    void configurePathMatching_shouldPrefixControllersWithTheServiceSegment() {
        PathMatchConfigurer configurer = mock(PathMatchConfigurer.class);

        config.configurePathMatching(configurer);

        // The gateway strips /api/v1 from /api/v1/social-service/** and leaves
        // /social-service, which this prefix reattaches: a service answers on the same
        // path whether it is called through the gateway or directly.
        verify(configurer).addPathPrefix(eq("/social-service"), any(HandlerTypePredicate.class));
    }

    @Test
    @DisplayName("the prefix applies to this service's own controllers only")
    void configurePathMatching_shouldTargetThisServicesControllersOnly() {
        PathMatchConfigurer configurer = mock(PathMatchConfigurer.class);
        ArgumentCaptor<HandlerTypePredicate> predicate =
                ArgumentCaptor.forClass(HandlerTypePredicate.class);

        config.configurePathMatching(configurer);
        verify(configurer).addPathPrefix(eq("/social-service"), predicate.capture());

        assertThat(predicate.getValue().test(RelationshipController.class)).isTrue();

        // Selecting on the @RestController annotation instead would also match springdoc's
        // OpenApiWebfluxResource, which served the specification at
        // /social-service/v3/api-docs — behind authentication — and left /v3/api-docs a 404.
        // ForeignController stands in for it: annotated, but not ours.
        assertThat(predicate.getValue().test(ForeignController.class)).isFalse();
        assertThat(predicate.getValue().test(PlainClass.class)).isFalse();
    }

    @RestController
    private static class ForeignController { }

    private static class PlainClass { }
}
