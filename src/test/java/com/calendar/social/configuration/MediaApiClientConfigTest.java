package com.calendar.social.configuration;

import com.calendar.social.exception.RequestIds;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MediaApiClientConfigTest {

    private final MediaApiClientConfig config = new MediaApiClientConfig();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> secretHeader = new AtomicReference<>();
    private final AtomicReference<String> requestIdHeader = new AtomicReference<>();

    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            secretHeader.set(exchange.getRequestHeaders().getFirst(UsersApiClientConfig.SECRET_HEADER));
            requestIdHeader.set(exchange.getRequestHeaders().getFirst(RequestIds.REQUEST_ID_HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mediaWebClient_shouldSendTheSocialSecretToTheConfiguredUrl() {
        StepVerifier.create(config.mediaWebClient(url, "social-secret").delete().uri("/internal/media")
                        .retrieve().toBodilessEntity())
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(requestedPath).hasValue("/internal/media");
        assertThat(secretHeader).hasValue("social-secret");
    }

    @Test
    void mediaWebClient_shouldPassTheRequestIdOn_toWelyMedia() {
        StepVerifier.create(config.mediaWebClient(url, "social-secret").delete().uri("/internal/media")
                        .retrieve().toBodilessEntity()
                        .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-from-gateway")))
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(requestIdHeader).hasValue("abcd1234-from-gateway");
    }

    @Test
    void mediaWebClient_shouldNotFailTheStartup_whenTheUrlAndTheSecretAreBlank() {
        // The service must start before the secret is sealed: an upload then fails with SCL-TEC-004.
        assertThatCode(() -> config.mediaWebClient("", "")).doesNotThrowAnyException();
    }
}
