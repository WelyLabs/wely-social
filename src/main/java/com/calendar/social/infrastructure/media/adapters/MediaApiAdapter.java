package com.calendar.social.infrastructure.media.adapters;

import com.calendar.social.domain.ports.MediaStore;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.media.models.MediaProblem;
import com.calendar.social.infrastructure.media.models.SignUrlsRequest;
import com.calendar.social.infrastructure.media.models.SignedUrlsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * Stores, removes and signs community pictures through wely-media's internal API, which owns the
 * bucket. A refused image answers SCL-BUS-020, an oversized one SCL-BUS-021; anything else becomes
 * SCL-TEC-004 (502).
 */
@Slf4j
@Component
public class MediaApiAdapter implements MediaStore {

    static final String MEDIA_PATH = "/internal/media";
    static final String PREFIX_PATH = "/internal/media/prefix";
    static final String URLS_PATH = "/internal/media/urls";
    /** wely-media's square crop, 512 px a side: a community picture is shown as a round badge. */
    static final String PROFILE = "AVATAR";
    /** Long enough to read a page, short enough that a shared link stops working. */
    static final long URL_TTL_SECONDS = 3600;
    /** wely-media wants a file name on the part; the key already names the object. */
    static final String UPLOAD_FILENAME = "upload";
    static final String INVALID_IMAGE = "MED-BUS-001";
    static final String IMAGE_TOO_LARGE = "MED-BUS-002";

    private final WebClient client;

    public MediaApiAdapter(@Qualifier("mediaWebClient") WebClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> store(String key, byte[] content) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", content, MediaType.APPLICATION_OCTET_STREAM).filename(UPLOAD_FILENAME);
        parts.part("key", key);
        parts.part("profile", PROFILE);
        return Mono.defer(() -> client.post()
                        .uri(MEDIA_PATH)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts.build()))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, true))
                        .toBodilessEntity()
                        .then())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("store " + key, error));
    }

    /** wely-media answers 204 for a missing key too, so deleting twice is not an error. */
    @Override
    public Mono<Void> delete(String key) {
        return Mono.defer(() -> client.delete()
                        .uri(uri -> uri.path(MEDIA_PATH).queryParam("key", "{key}").build(key))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, false))
                        .toBodilessEntity()
                        .then())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("delete " + key, error));
    }

    @Override
    public Mono<Void> deletePrefix(String prefix) {
        return Mono.defer(() -> client.delete()
                        .uri(uri -> uri.path(PREFIX_PATH).queryParam("prefix", "{prefix}").build(prefix))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, false))
                        .toBodilessEntity()
                        .then())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("purge " + prefix, error));
    }

    /** One call: the caller keeps to 200 keys. */
    @Override
    public Mono<Map<String, String>> urls(Collection<String> keys) {
        return Mono.defer(() -> client.post()
                        .uri(URLS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new SignUrlsRequest(keys, URL_TTL_SECONDS))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, false))
                        .bodyToMono(SignedUrlsResponse.class))
                .map(response -> response.urls() == null ? Map.<String, String>of() : response.urls())
                .defaultIfEmpty(Map.of())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("sign " + keys.size() + " keys", error));
    }

    /** wely-media's code decides: a refused upload is the client's fault, the rest is ours. */
    private static Mono<RuntimeException> toException(ClientResponse response, boolean upload) {
        int status = response.statusCode().value();
        return response.bodyToMono(MediaProblem.class)
                .map(MediaProblem::code)
                .onErrorReturn("")
                .defaultIfEmpty("")
                .map(code -> {
                    RuntimeException error = exceptionFor(code, upload);
                    if (error instanceof TechnicalException) {
                        log.warn("wely-media answered {} {}", status, code);
                    }
                    return error;
                });
    }

    /** Only an upload carries an image the user chose: any other refusal is ours. */
    static RuntimeException exceptionFor(String mediaCode, boolean upload) {
        if (upload && INVALID_IMAGE.equals(mediaCode)) {
            return new BusinessException(BusinessErrorCode.COMMUNITY_PICTURE_INVALID);
        }
        if (upload && IMAGE_TOO_LARGE.equals(mediaCode)) {
            return new BusinessException(BusinessErrorCode.COMMUNITY_PICTURE_TOO_LARGE);
        }
        return new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE);
    }

    private static boolean isUnexpected(Throwable error) {
        return !(error instanceof BusinessException) && !(error instanceof TechnicalException);
    }

    private static TechnicalException unavailable(String what, Throwable error) {
        log.warn("Could not {} on wely-media: {}", what, error.toString());
        return new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE);
    }
}
