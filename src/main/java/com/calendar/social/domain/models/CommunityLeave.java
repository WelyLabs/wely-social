package com.calendar.social.domain.models;

/**
 * What leaving a community did.
 *
 * @param newAdminId the member promoted because the last admin left, or null
 */
public record CommunityLeave(boolean communityDeleted, String newAdminId) {

    public static CommunityLeave left() {
        return new CommunityLeave(false, null);
    }

    public static CommunityLeave handedOverTo(String newAdminId) {
        return new CommunityLeave(false, newAdminId);
    }

    public static CommunityLeave deleted() {
        return new CommunityLeave(true, null);
    }
}
