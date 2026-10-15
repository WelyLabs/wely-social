package com.calendar.social.domain.models;

public enum FriendshipEventType {
    /** fromUser sent a friend request to toUser. */
    REQUESTED,
    /** fromUser accepted the request toUser had sent. */
    ACCEPTED
}
