package com.calendar.social.infrastructure.users.adapters;

import com.calendar.social.configuration.UsersApiClientConfig;
import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.users.mappers.DeletedUsersMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class UsersApiDeletedUsersAdapterTest {

    private static final String SECRET = "reader-secret-value";
    // Generous for a slow CI runner's first request; only the time-out case uses the short one.
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);
    private static final String ID = "3f0c2a4e-8b1d-4c6a-9e2f-1a2b3c4d5e6f";

    private final DeletedUsersMapper mapper = Mappers.getMapper(DeletedUsersMapper.class);
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private final AtomicReference<String> secretHeader = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMillis = 0;
    private volatile String body = "{\"items\":[{\"userId\":\"" + ID.toUpperCase() + "\","
            + "\"deletedAt\":\"2026-10-15T14:03:27.123Z\",\"extra\":true}],\"next\":\"MjAyNg\"}";

    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestedUri.set(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
            secretHeader.set(exchange.getRequestHeaders().getFirst(UsersApiClientConfig.SECRET_HEADER));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
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

    private UsersApiDeletedUsersAdapter adapter(String url, String secret) {
        return adapter(url, secret, TIMEOUT);
    }

    private UsersApiDeletedUsersAdapter adapter(String url, String secret, Duration timeout) {
        return new UsersApiDeletedUsersAdapter(new UsersApiClientConfig().usersWebClient(url, secret), mapper,
                url, secret, timeout);
    }

    private UsersApiDeletedUsersAdapter adapter() {
        return adapter(url, SECRET);
    }

    private static boolean usersUnavailable(Throwable error) {
        return error instanceof TechnicalException technical
                && technical.getErrorCode() == TechnicalErrorCode.USERS_UNAVAILABLE;
    }

    @Test
    void page_shouldReadTheFirstPage_withTheReaderSecretAndTheLimit() {
        StepVerifier.create(adapter().page(null, 500))
                .expectNext(new DeletedUsersPage(
                        List.of(new DeletedUser(ID, Instant.parse("2026-10-15T14:03:27.123Z"))), "MjAyNg"))
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo(UsersApiDeletedUsersAdapter.PATH + "?limit=500");
        assertThat(secretHeader.get()).isEqualTo(SECRET);
    }

    @Test
    void page_shouldSendTheCursorEncoded_whenAfterIsGiven() {
        body = "{\"items\":[],\"next\":null}";

        StepVerifier.create(adapter().page("a+b/c=", 2))
                .expectNext(new DeletedUsersPage(List.of(), null))
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo(UsersApiDeletedUsersAdapter.PATH + "?limit=2&after=a%2Bb%2Fc%3D");
    }

    @Test
    void page_shouldFailWithoutCalling_whenTheSecretIsBlank() {
        StepVerifier.create(adapter(url, " ").page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();

        assertThat(calls).hasValue(0);
    }

    @Test
    void page_shouldFailWithoutACause_whenNotConfigured() {
        // Detected here, before any call: there is no underlying failure to carry.
        StepVerifier.create(adapter("", SECRET).page(null, 500))
                .verifyErrorSatisfies(error -> assertThat(error.getCause()).isNull());
    }

    @Test
    void page_shouldFailWithoutCalling_whenTheUrlIsBlank() {
        StepVerifier.create(adapter("", SECRET).page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();

        assertThat(calls).hasValue(0);
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenWelyUsersAnswers401(CapturedOutput output) {
        status = 401;
        body = "{\"code\":\"USR-TEC-401\"}";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();

        assertThat(output).contains("Could not list deleted accounts").doesNotContain(SECRET);
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenWelyUsersAnswers5xx() {
        status = 503;

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }

    @Test
    void page_shouldKeepTheHttpErrorAsCause_whenWelyUsersAnswers5xx() {
        status = 503;

        StepVerifier.create(adapter().page(null, 500))
                .verifyErrorSatisfies(error -> assertThat(error.getCause())
                        .isInstanceOfSatisfying(WebClientResponseException.class,
                                http -> assertThat(http.getStatusCode().value()).isEqualTo(503)));
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenWelyUsersTimesOut() {
        delayMillis = 2_000;

        StepVerifier.create(adapter(url, SECRET, SHORT_TIMEOUT).page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void page_shouldKeepTheTimeoutAsCause_whenWelyUsersTimesOut() {
        delayMillis = 2_000;

        StepVerifier.create(adapter(url, SECRET, SHORT_TIMEOUT).page(null, 500))
                .expectErrorSatisfies(error -> assertThat(error.getCause()).isInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenWelyUsersIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String unreachable = "http://127.0.0.1:" + closedPort;

        StepVerifier.create(adapter(unreachable, SECRET).page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenTheBodyIsUnreadable() {
        body = "<html>not json</html>";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenAnIdIsNotAUuid() {
        body = "{\"items\":[{\"userId\":\"camille\",\"deletedAt\":\"2026-10-15T14:03:27Z\"}],\"next\":null}";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenAnItemHasNoUserId() {
        body = "{\"items\":[{\"deletedAt\":\"2026-10-15T14:03:27Z\"}],\"next\":null}";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenItemsAreMissing() {
        body = "{\"next\":null}";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }

    @Test
    void page_shouldKeepTheRejectionAsCause_whenThePageIsIncomplete() {
        body = "{\"next\":null}";

        StepVerifier.create(adapter().page(null, 500))
                .verifyErrorSatisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("incomplete deleted-users page"));
    }

    @Test
    void page_shouldFailWithUsersUnavailable_whenTheBodyIsEmpty() {
        body = "";

        StepVerifier.create(adapter().page(null, 500))
                .expectErrorMatches(UsersApiDeletedUsersAdapterTest::usersUnavailable)
                .verify();
    }
}
