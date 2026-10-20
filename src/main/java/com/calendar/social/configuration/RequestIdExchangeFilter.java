package com.calendar.social.configuration;

import com.calendar.social.exception.RequestIds;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Passes the current request id on to another Wely service, read from the Reactor Context that
 * {@link RequestIdWebFilter} fills. Background calls have none, and go without the header.
 *
 * <p>Only on clients for Wely services: an external party (Keycloak, a storage provider) has no use
 * for it and should not learn it.
 */
public class RequestIdExchangeFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(context -> next.exchange(context.<String>getOrEmpty(RequestIds.REQUEST_ID_ATTRIBUTE)
                .map(id -> ClientRequest.from(request).header(RequestIds.REQUEST_ID_HEADER, id).build())
                .orElse(request)));
    }
}
