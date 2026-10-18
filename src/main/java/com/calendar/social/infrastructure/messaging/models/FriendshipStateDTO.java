package com.calendar.social.infrastructure.messaging.models;

import java.time.Instant;

/**
 * Sent on the compacted {@code FRIENDSHIP_STATE} topic, keyed by {@code a:b}: the pair's current
 * friendship. Two users who are not friends have no record there, only a tombstone (null value).
 *
 * @param a         the lower of the two ids
 * @param b         the higher of the two ids
 * @param friends   always true: a pair that is not friends is a tombstone
 * @param closeness FRIEND, CLOSE or INTIMATE
 * @param since     when they became friends
 * @param version   the outbox record's version, shared with FRIENDSHIP_CHANGED, for consumers to drop stale ones
 */
public record FriendshipStateDTO(
        String a,
        String b,
        boolean friends,
        String closeness,
        Instant since,
        long version
) {}
