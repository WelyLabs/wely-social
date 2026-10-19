package com.calendar.social.domain.ports;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/** Where community pictures are kept, privately: nothing stored is readable without a signed URL. */
public interface MediaStore {

    /**
     * Crops the picture to a square and stores it under {@code key}. Fails with COMMUNITY_PICTURE_INVALID,
     * COMMUNITY_PICTURE_TOO_LARGE or MEDIA_UNAVAILABLE.
     */
    Mono<Void> store(String key, byte[] content);

    /** Removing a missing key is not an error. */
    Mono<Void> delete(String key);

    /** Removes every object under {@code prefix}, which ends with '/'; nothing under it is not an error. */
    Mono<Void> deletePrefix(String prefix);

    /** Short-lived signed URLs, by key, for at most 200 keys; a key the store does not know is left out. */
    Mono<Map<String, String>> urls(Collection<String> keys);
}
