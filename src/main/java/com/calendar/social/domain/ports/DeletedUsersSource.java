package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.DeletedUsersPage;
import reactor.core.publisher.Mono;

/** The authority on deleted accounts; a source that cannot be read fails with USERS_UNAVAILABLE. */
public interface DeletedUsersSource {

    /** One page of tombstones after the {@code after} cursor, or the first page when it is null. */
    Mono<DeletedUsersPage> page(String after, int limit);
}
