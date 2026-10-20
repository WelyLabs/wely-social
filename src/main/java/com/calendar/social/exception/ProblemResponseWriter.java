package com.calendar.social.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Writes a problem straight to the response, for the places no controller advice reaches: web
 * filters, the security entry point and access-denied handler.
 *
 * <p>Through the application's own codecs, so the body is serialized exactly as a problem returned
 * by {@link GlobalErrorHandler} from a controller.
 */
@Component
public class ProblemResponseWriter {

    private final ServerResponse.Context context;

    public ProblemResponseWriter(ServerCodecConfigurer codecs) {
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    public Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        return ServerResponse.status(HttpStatus.valueOf(problem.getStatus()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, context));
    }
}
