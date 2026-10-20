package com.calendar.social.exception;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Answers an authenticated request the security rules refuse with our 403 problem ({@code SCL-REQ-002}). */
@Component
public class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final GlobalErrorHandler errorHandler;
    private final ProblemResponseWriter writer;

    public ProblemAccessDeniedHandler(GlobalErrorHandler errorHandler, ProblemResponseWriter writer) {
        this.errorHandler = errorHandler;
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return writer.write(exchange,
                errorHandler.handle(new BusinessException(BusinessErrorCode.ACCESS_DENIED), exchange));
    }
}
