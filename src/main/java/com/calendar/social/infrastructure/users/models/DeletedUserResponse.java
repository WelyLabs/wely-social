package com.calendar.social.infrastructure.users.models;

import java.time.Instant;
import java.util.UUID;

/** One tombstone of the listing; a UUID type, so anything but a UUID makes the body unreadable. */
public record DeletedUserResponse(UUID userId, Instant deletedAt) {
}
