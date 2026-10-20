package com.calendar.social.configuration;

import com.calendar.social.exception.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.regex.Pattern;

/**
 * Adopts the gateway's request id, so one id follows a request from the gateway to these logs, the
 * problem answered, and the calls made to other Wely services.
 *
 * <p>The header is only trusted when it looks like an id: anything else (missing, too long, odd
 * characters that could forge a log line) falls back to the WebFlux request id. First of all filters,
 * so the security chain's refusals carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER);
        String id = header != null && VALID_ID.matcher(header).matches() ? header : exchange.getRequest().getId();

        exchange.getAttributes().put(RequestIds.REQUEST_ID_ATTRIBUTE, id);
        exchange.getResponse().getHeaders().set(RequestIds.REQUEST_ID_HEADER, id);
        return chain.filter(exchange).contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, id));
    }
}
