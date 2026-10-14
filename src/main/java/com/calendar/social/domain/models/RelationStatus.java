package com.calendar.social.domain.models;

/** Where the caller stands with another user; SELF when they look at their own profile. */
public enum RelationStatus {
    SELF,
    NONE,
    FRIENDS,
    SENT_BY_ME,
    SENT_BY_THEM
}
