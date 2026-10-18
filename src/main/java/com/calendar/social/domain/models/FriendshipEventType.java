package com.calendar.social.domain.models;

/** What happened to a pair. fromUser is the actor, except for ACCEPTED, where it is the acceptor. */
public enum FriendshipEventType {
    /** fromUser sent a friend request to toUser. */
    REQUESTED,
    /** fromUser accepted the request toUser had sent. */
    ACCEPTED,
    /** fromUser rejected the request toUser had sent. */
    REJECTED,
    /** fromUser withdrew the request it had sent toUser. Part of the contract; no operation emits it yet. */
    CANCELLED,
    /** fromUser ended the friendship, or deleted its account. */
    REMOVED,
    /** The pair's closeness took effect: an agreed step up, or a one-sided step down by fromUser. */
    CLOSENESS_CHANGED
}
