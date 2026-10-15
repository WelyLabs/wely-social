package com.calendar.social.domain.models;

/** Who can find a community. */
public enum CommunityVisibility {
    /** Listed in the discovery tab; anyone joins at once, without approval. */
    PUBLIC,
    /** Invisible to non-members, as if it did not exist; joined only through an invitation. */
    PRIVATE
}
