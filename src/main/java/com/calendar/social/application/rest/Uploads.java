package com.calendar.social.application.rest;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

/** Reads the picture of a multipart upload: its part {@code file}, at most 10 MB. */
final class Uploads {

    /** Above this, refused before wely-media is called. The apps send pictures already scaled down. */
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final String FILE_PART = "file";

    private Uploads() {
    }

    /** Fails with SCL-BUS-020 when there is no file or it is empty, SCL-BUS-021 above 10 MB. */
    static Mono<byte[]> fileOf(Mono<MultiValueMap<String, Part>> form) {
        return form
                .flatMap(parts -> parts.getFirst(FILE_PART) instanceof FilePart file
                        ? DataBufferUtils.join(file.content(), MAX_BYTES).map(Uploads::bytesOf)
                        : Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_PICTURE_INVALID)))
                .onErrorMap(DataBufferLimitException.class,
                        error -> new BusinessException(BusinessErrorCode.COMMUNITY_PICTURE_TOO_LARGE))
                .filter(bytes -> bytes.length > 0)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.COMMUNITY_PICTURE_INVALID)));
    }

    private static byte[] bytesOf(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }
}
