package com.calendar.social.exception;

import org.springframework.web.server.ServerWebExchange;

/**
 * The request id that correlates a response, its logs and the calls it makes to other Wely services.
 *
 * <p>Set by {@code RequestIdWebFilter} from the gateway's {@value #REQUEST_ID_HEADER}; without it
 * (a unit test, a request that skipped the filter) the WebFlux request id stands in.
 */
public final class RequestIds {

    /** The header carrying the id between the gateway, the services and the response. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Exchange attribute and Reactor Context key holding the id for this request. */
    public static final String REQUEST_ID_ATTRIBUTE = "wely.requestId";

    private RequestIds() {
    }

    public static String of(ServerWebExchange exchange) {
        String id = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);
        return id != null ? id : exchange.getRequest().getId();
    }
}
