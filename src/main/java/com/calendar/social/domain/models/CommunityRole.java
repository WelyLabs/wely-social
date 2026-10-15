package com.calendar.social.domain.models;

/** A member's role. A community may have several admins, never none while it has members. */
public enum CommunityRole {
    /** Edits and deletes the community, manages its members and invitations, moderates its events. */
    ADMIN,
    MEMBER
}
