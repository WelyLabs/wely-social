package com.calendar.social.domain.ports;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Permanent markers of deleted accounts: only the businessId and two timestamps, never a name. */
public interface DeletedUserMarkers {

    /** Creates the user's marker if there is none; a replay keeps the first {@code deletedAt}. */
    Mono<Void> markDeleted(String userId, Instant deletedAt);

    /** Records when the erasure completed, keeping the first value; does nothing without a marker. */
    Mono<Void> markErased(String userId, Instant erasedAt);

    /** The ones among {@code userIds} whose erasure completed; no query for an empty input. */
    Mono<Set<String>> erasedAmong(Collection<String> userIds);
}
