package com.calendar.social.exception;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers our problem for failures no controller advice sees: those raised in a web filter, or
 * before a handler is chosen.
 *
 * <p>Ordered before Boot's {@code DefaultErrorWebExceptionHandler} (-1), whose JSON has neither
 * {@code code} nor {@code requestId}. The mapping, logging and counting are
 * {@link GlobalErrorHandler}'s, so a failure answers the same wherever it is raised.
 */
@Component
@Order(ProblemWebExceptionHandler.ORDER)
public class ProblemWebExceptionHandler implements WebExceptionHandler {

    static final int ORDER = -2;

    private final GlobalErrorHandler errorHandler;
    private final ProblemResponseWriter writer;

    public ProblemWebExceptionHandler(GlobalErrorHandler errorHandler, ProblemResponseWriter writer) {
        this.errorHandler = errorHandler;
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Headers and part of the body may already be on the wire: nothing sound can be written now.
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return writer.write(exchange, errorHandler.handle(ex, exchange));
    }
}
