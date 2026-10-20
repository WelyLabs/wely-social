package com.calendar.social.exception;

import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers a request without a valid token with our 401 problem ({@code SCL-REQ-001}).
 *
 * <p>The {@code WWW-Authenticate: Bearer ...} header still comes from Spring's
 * {@link BearerTokenServerAuthenticationEntryPoint}, which knows why the token was refused
 * ({@code error="invalid_token"}, its description). It runs on a response it cannot complete, so the
 * problem body can follow.
 */
@Component
public class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final ServerAuthenticationEntryPoint headers = new BearerTokenServerAuthenticationEntryPoint();
    private final GlobalErrorHandler errorHandler;
    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(GlobalErrorHandler errorHandler, ProblemResponseWriter writer) {
        this.errorHandler = errorHandler;
        this.writer = writer;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        ServerWebExchange headersOnly = exchange.mutate().response(new HeadersOnlyResponse(exchange)).build();
        return headers.commence(headersOnly, ex)
                .then(Mono.defer(() -> writer.write(exchange, errorHandler.handle(
                        new BusinessException(BusinessErrorCode.AUTHENTICATION_REQUIRED), exchange))));
    }

    /** Lets the delegate set the status and headers, but not complete the response. */
    private static final class HeadersOnlyResponse extends ServerHttpResponseDecorator {

        HeadersOnlyResponse(ServerWebExchange exchange) {
            super(exchange.getResponse());
        }

        @Override
        public Mono<Void> setComplete() {
            return Mono.empty();
        }
    }
}
