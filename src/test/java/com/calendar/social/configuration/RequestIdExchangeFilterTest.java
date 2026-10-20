package com.calendar.social.configuration;

import com.calendar.social.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdExchangeFilterTest {

    private final RequestIdExchangeFilter filter = new RequestIdExchangeFilter();
    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();
    private final ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://wely-users/x")).build();

    private Mono<ClientResponse> exchange() {
        return filter.filter(request, outgoing -> {
            sent.set(outgoing);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        });
    }

    @Test
    void filter_shouldPassTheRequestIdOn_whenTheContextHoldsOne() {
        exchange().contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-from-gateway"))
                .block(Duration.ofSeconds(10));

        assertThat(sent.get().headers().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo("abcd1234-from-gateway");
    }

    @Test
    void filter_shouldSendTheRequestUnchanged_whenThereIsNoRequestId() {
        // A background call (the reconciliation loop) has no request behind it.
        exchange().block(Duration.ofSeconds(10));

        assertThat(sent.get()).isSameAs(request);
        assertThat(sent.get().headers().containsHeader(RequestIds.REQUEST_ID_HEADER)).isFalse();
    }
}
