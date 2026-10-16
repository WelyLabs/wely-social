package com.calendar.social.domain.models;

import java.time.Instant;

/** An account wely-users deleted, as its tombstone listing reports it. */
public record DeletedUser(String userId, Instant deletedAt) {
}
