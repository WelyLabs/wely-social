package com.calendar.social.configuration;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UsersApiClientConfigTest {

    private static final String SECRET = "reader-secret-value";
    private static final String PATH = "/user-service/internal/deleted-users";
    private static final String BODY = "{\"items\":[],\"next\":null}";
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    private final UsersApiClientConfig config = new UsersApiClientConfig();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> secretHeader = new AtomicReference<>();
    private volatile long delayMillis = 0;
    private volatile int status = 200;

    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            secretHeader.set(exchange.getRequestHeaders().getFirst(UsersApiClientConfig.SECRET_HEADER));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Mono<String> get(WebClient client) {
        return client.get().uri(PATH).retrieve().bodyToMono(String.class);
    }

    @Test
    void usersWebClient_shouldSendTheReaderSecretToTheConfiguredUrl() {
        StepVerifier.create(get(config.usersWebClient(url, SECRET))).expectNext(BODY).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(requestedPath).hasValue(PATH);
        assertThat(secretHeader).hasValue(SECRET);
    }

    @Test
    void usersWebClient_shouldSendAnEmptySecret_whenTheSecretIsBlank() {
        StepVerifier.create(get(config.usersWebClient(url, ""))).expectNext(BODY).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(secretHeader).hasValue("");
    }

    @Test
    void usersWebClient_shouldNotFailTheStartup_whenTheUrlAndTheSecretAreBlank() {
        // The service must start before the secret is sealed: the adapter then refuses every page itself.
        assertThatCode(() -> config.usersWebClient("", "")).doesNotThrowAnyException();
    }

    @Test
    void webClient_shouldKeepTheSecretOutOfItsFailures_whenWelyUsersIsUnreachable() throws IOException {
        // Regression guard: the secret travels in a header, and neither the connection error nor its
        // "Request to GET ..." checkpoint names headers. A secret in the URL would leak: logback-spring.xml masks that.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        StepVerifier.create(get(UsersApiClientConfig.webClient("http://127.0.0.1:" + closedPort, SECRET, SHORT_TIMEOUT)))
                .expectErrorSatisfies(error -> assertThat(traceOf(error)).contains("Request to GET").doesNotContain(SECRET))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void webClient_shouldKeepTheSecretOutOfItsFailures_whenWelyUsersRefusesIt() {
        status = 401;

        StepVerifier.create(get(UsersApiClientConfig.webClient(url, SECRET, SHORT_TIMEOUT)))
                .expectErrorSatisfies(error -> assertThat(traceOf(error)).contains("401").doesNotContain(SECRET))
                .verify(Duration.ofSeconds(10));
    }

    private static String traceOf(Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

    @Test
    void webClient_shouldFailThroughItsOwnResponseTimeout_whenWelyUsersIsSlow() {
        delayMillis = 2_000;

        StepVerifier.create(get(UsersApiClientConfig.webClient(url, SECRET, SHORT_TIMEOUT)))
                .expectErrorSatisfies(error -> assertThat(error).hasCauseInstanceOf(ReadTimeoutException.class))
                .verify(Duration.ofSeconds(1));
    }
}
