package com.calendar.social.domain.models;

/**
 * A change to a pair, recorded with the change itself. The pair's state after it — closeness,
 * since — and its version are read from the graph when the record is written.
 *
 * @param previousCloseness the pair's closeness before the change; null when they were not friends
 */
public record FriendshipEvent(
        FriendshipEventType type,
        String fromUserId,
        String toUserId,
        Closeness previousCloseness
) {

    /** A change from a pair that was not friends before it: a request, an acceptance, a rejection. */
    public static FriendshipEvent betweenNonFriends(FriendshipEventType type, String fromUserId, String toUserId) {
        return new FriendshipEvent(type, fromUserId, toUserId, null);
    }
}
