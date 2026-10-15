package com.calendar.social.domain.models;

import java.time.Instant;

/** A deleted account as published on USER_DELETED; deletedAt is null on a record from an older producer. */
public record UserDeletedEventDTO(
        String userId,
        Instant deletedAt
) {
}
