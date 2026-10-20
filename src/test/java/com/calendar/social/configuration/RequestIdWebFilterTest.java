package com.calendar.social.configuration;

import com.calendar.social.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {

    private final RequestIdWebFilter filter = new RequestIdWebFilter();
    private final AtomicReference<String> contextId = new AtomicReference<>();

    /** Records what the rest of the chain sees in the Reactor Context. */
    private final WebFilterChain chain = exchange -> Mono.deferContextual(context -> {
        contextId.set(context.<String>getOrEmpty(RequestIds.REQUEST_ID_ATTRIBUTE).orElse(null));
        return Mono.empty();
    });

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block(Duration.ofSeconds(10));
        return exchange;
    }

    private static void assertId(MockServerWebExchange exchange, String expected) {
        assertThat(exchange.<String>getAttribute(RequestIds.REQUEST_ID_ATTRIBUTE)).isEqualTo(expected);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo(expected);
    }

    @Test
    void filter_shouldKeepTheGatewayId_whenItLooksLikeAnId() {
        String id = "abcd1234-5678-90ef-abcd-1234567890ef";

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/x").header(RequestIds.REQUEST_ID_HEADER, id));

        assertId(exchange, id);
        assertThat(contextId).hasValue(id);
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderIsMissing() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/x"));

        assertId(exchange, exchange.getRequest().getId());
        assertThat(contextId).hasValue(exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderCouldForgeALogLine() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/x")
                .header(RequestIds.REQUEST_ID_HEADER, "abcd1234\nERROR forged line"));

        assertId(exchange, exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderIsTooShortOrTooLong() {
        MockServerWebExchange tooShort = run(MockServerHttpRequest.get("/x").header(RequestIds.REQUEST_ID_HEADER, "abc"));
        MockServerWebExchange tooLong = run(MockServerHttpRequest.get("/x")
                .header(RequestIds.REQUEST_ID_HEADER, "a".repeat(65)));

        assertId(tooShort, tooShort.getRequest().getId());
        assertId(tooLong, tooLong.getRequest().getId());
    }
}
