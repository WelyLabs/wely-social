package com.calendar.social.infrastructure.media.adapters;

import com.calendar.social.configuration.MediaApiClientConfig;
import com.calendar.social.configuration.UsersApiClientConfig;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real HTTP server on a free port: the multipart body, the secret and the error codes are what is tested. */
class MediaApiAdapterTest {

    private static final String SECRET = "s3cret";
    private static final String KEY = "communities/c1/picture-a.jpg";

    private HttpServer server;
    private MediaApiAdapter adapter;

    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String body = "";
    private volatile String method;
    private volatile String path;
    private volatile String query;
    private volatile String secret;
    private volatile String requestContentType;
    private volatile String requestBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getQuery();
            secret = exchange.getRequestHeaders().getFirst(UsersApiClientConfig.SECRET_HEADER);
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        adapter = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static MediaApiAdapter adapterFor(String url) {
        return new MediaApiAdapter(new MediaApiClientConfig().mediaWebClient(url, SECRET));
    }

    private void answers(int status, String code) {
        this.status = status;
        this.contentType = "application/problem+json";
        this.body = "{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code + "\"}";
    }

    private static boolean failsWith(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException exception && exception.getErrorCode() == code;
    }

    private static boolean isUnavailable(Throwable error) {
        return error instanceof TechnicalException exception && exception.getErrorCode() == TechnicalErrorCode.MEDIA_UNAVAILABLE;
    }

    @Test
    void store_shouldSendTheFileKeyAndSquareProfile_withTheSecret() {
        status = 201;
        body = "{\"key\":\"" + KEY + "\",\"width\":512,\"height\":512,\"contentType\":\"image/jpeg\"}";

        StepVerifier.create(adapter.store(KEY, "JPEGDATA".getBytes(StandardCharsets.ISO_8859_1))).verifyComplete();

        assertThat(method).isEqualTo("POST");
        assertThat(path).isEqualTo(MediaApiAdapter.MEDIA_PATH);
        assertThat(secret).isEqualTo(SECRET);
        assertThat(requestContentType).startsWith("multipart/form-data");
        assertThat(requestBody)
                .contains("name=\"file\"; filename=\"upload\"").contains("JPEGDATA")
                .contains("name=\"key\"").contains(KEY)
                .contains("name=\"profile\"").contains("AVATAR");
    }

    @Test
    void store_shouldAnswerPictureInvalid_whenWelyMediaRefusesTheImage() {
        answers(400, "MED-BUS-001");

        StepVerifier.create(adapter.store(KEY, new byte[]{1}))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_PICTURE_INVALID))
                .verify();
    }

    @Test
    void store_shouldAnswerTooLarge_whenWelyMediaFindsItTooLarge() {
        answers(413, "MED-BUS-002");

        StepVerifier.create(adapter.store(KEY, new byte[]{1}))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COMMUNITY_PICTURE_TOO_LARGE))
                .verify();
    }

    @Test
    void store_shouldAnswerUnavailable_forEveryOtherRefusal() {
        for (Map.Entry<Integer, String> refusal : Map.of(403, "MED-BUS-003", 401, "MED-REQ-001", 502, "MED-TEC-001").entrySet()) {
            answers(refusal.getKey(), refusal.getValue());

            StepVerifier.create(adapter.store(KEY, new byte[]{1}))
                    .expectErrorMatches(MediaApiAdapterTest::isUnavailable)
                    .verify();
        }
    }

    @Test
    void store_shouldAnswerUnavailable_whenTheErrorIsNotAProblem() {
        status = 500;
        contentType = "text/html";
        body = "<html>oops</html>";

        StepVerifier.create(adapter.store(KEY, new byte[]{1}))
                .expectErrorMatches(MediaApiAdapterTest::isUnavailable)
                .verify();
    }

    @Test
    void store_shouldAnswerUnavailable_whenWelyMediaCannotBeReached() {
        server.stop(0);
        MediaApiAdapter unreachable = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());

        StepVerifier.create(unreachable.store(KEY, new byte[]{1}))
                .expectErrorMatches(MediaApiAdapterTest::isUnavailable)
                .verify();
    }

    @Test
    void store_shouldKeepTheConnectionFailureAsCause_whenWelyMediaCannotBeReached() {
        server.stop(0);
        MediaApiAdapter unreachable = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());

        StepVerifier.create(unreachable.store(KEY, new byte[]{1}))
                .verifyErrorSatisfies(error -> assertThat(error.getCause()).isInstanceOf(WebClientRequestException.class));
    }

    @Test
    void urls_shouldKeepTheDecodingFailureAsCause_whenTheAnswerIsUnreadable() {
        status = 200;
        body = "<html>not json</html>";

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .matches(MediaApiAdapterTest::isUnavailable)
                        .cause().isInstanceOf(DecodingException.class));
    }

    @Test
    void store_shouldAnswerUnavailableWithoutACause_whenWelyMediaRefuses() {
        // An HTTP refusal is an answer, not an exception: there is nothing underneath to carry.
        answers(502, "MED-TEC-001");

        StepVerifier.create(adapter.store(KEY, new byte[]{1}))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .matches(MediaApiAdapterTest::isUnavailable)
                        .hasNoCause());
    }

    @Test
    void delete_shouldNameTheKeyInTheQuery() {
        status = 204;

        StepVerifier.create(adapter.delete(KEY)).verifyComplete();

        assertThat(method).isEqualTo("DELETE");
        assertThat(path).isEqualTo(MediaApiAdapter.MEDIA_PATH);
        assertThat(query).isEqualTo("key=" + KEY);
        assertThat(secret).isEqualTo(SECRET);
    }

    @Test
    void delete_shouldAnswerUnavailable_evenWhenWelyMediaBlamesTheImage() {
        answers(400, "MED-BUS-001");

        StepVerifier.create(adapter.delete(KEY)).expectErrorMatches(MediaApiAdapterTest::isUnavailable).verify();
    }

    @Test
    void deletePrefix_shouldNameThePrefixInTheQuery() {
        status = 204;

        StepVerifier.create(adapter.deletePrefix("communities/c1/")).verifyComplete();

        assertThat(method).isEqualTo("DELETE");
        assertThat(path).isEqualTo(MediaApiAdapter.PREFIX_PATH);
        assertThat(query).isEqualTo("prefix=communities/c1/");
        assertThat(secret).isEqualTo(SECRET);
    }

    @Test
    void deletePrefix_shouldAnswerUnavailable_whenWelyMediaFails() {
        answers(502, "MED-TEC-001");

        StepVerifier.create(adapter.deletePrefix("communities/c1/")).expectErrorMatches(MediaApiAdapterTest::isUnavailable).verify();
    }

    @Test
    void deletePrefix_shouldAnswerUnavailable_whenWelyMediaCannotBeReached() {
        server.stop(0);
        MediaApiAdapter unreachable = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());

        StepVerifier.create(unreachable.deletePrefix("communities/c1/")).expectErrorMatches(MediaApiAdapterTest::isUnavailable).verify();
    }

    @Test
    void urls_shouldSendTheKeysAndLifetime_andReadTheUrlsByKey() {
        body = "{\"urls\":{\"" + KEY + "\":\"https://media/a\"}}";

        StepVerifier.create(adapter.urls(List.of(KEY, "communities/c2/picture-b.jpg")))
                .expectNext(Map.of(KEY, "https://media/a"))
                .verifyComplete();

        assertThat(method).isEqualTo("POST");
        assertThat(path).isEqualTo(MediaApiAdapter.URLS_PATH);
        assertThat(secret).isEqualTo(SECRET);
        assertThat(requestBody).contains("\"keys\":[\"" + KEY + "\",\"communities/c2/picture-b.jpg\"]")
                .contains("\"ttlSeconds\":" + MediaApiAdapter.URL_TTL_SECONDS);
    }

    @Test
    void urls_shouldBeEmpty_whenTheAnswerHasNoUrls() {
        body = "{}";

        StepVerifier.create(adapter.urls(List.of(KEY))).expectNext(Map.of()).verifyComplete();
    }

    @Test
    void urls_shouldAnswerUnavailable_whenWelyMediaRefuses() {
        answers(401, "MED-REQ-001");

        StepVerifier.create(adapter.urls(List.of(KEY))).expectErrorMatches(MediaApiAdapterTest::isUnavailable).verify();
    }

    @Test
    void exceptionFor_shouldOnlyBlameTheImage_onAnUpload() {
        assertThat(MediaApiAdapter.exceptionFor("MED-BUS-001", false)).isInstanceOf(TechnicalException.class);
        assertThat(MediaApiAdapter.exceptionFor("MED-BUS-002", false)).isInstanceOf(TechnicalException.class);
        assertThat(MediaApiAdapter.exceptionFor(null, true)).isInstanceOf(TechnicalException.class);
        assertThat(failsWith(MediaApiAdapter.exceptionFor("MED-BUS-001", true), BusinessErrorCode.COMMUNITY_PICTURE_INVALID)).isTrue();
    }
}
