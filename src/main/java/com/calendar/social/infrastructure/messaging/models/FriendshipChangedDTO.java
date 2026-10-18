package com.calendar.social.infrastructure.messaging.models;

import java.time.Instant;

/**
 * Sent on {@code FRIENDSHIP_CHANGED}, keyed by the pair (the two ids sorted, joined with ':').
 *
 * @param type      REQUESTED, ACCEPTED, REJECTED, CANCELLED, REMOVED, CLOSENESS_CHANGED, or SNAPSHOT
 *                  for the one-time backfill of existing friendships
 * @param closeness the pair's closeness after the change; null when they are not friends
 * @param previousCloseness the pair's closeness before it; null when they were not friends, and for SNAPSHOT
 * @param since     when they became friends; null when they are not
 * @param version   rises with every record of the pair, so a consumer can drop stale or reordered ones
 */
public record FriendshipChangedDTO(
        String type,
        String fromUserId,
        String fromUserName,
        String toUserId,
        String closeness,
        String previousCloseness,
        Instant since,
        long version,
        Instant occurredAt
) {}
