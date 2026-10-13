package com.calendar.social.domain.models;

/**
 * A user's profile after a change, as wely-users publishes it on USER_MODIFIED. The names are not
 * kept in the graph but are declared all the same: the record mirrors the topic's contract, so
 * reading it does not depend on the converter tolerating unknown fields.
 */
public record UserModifiedEventDTO(
        String userId,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        String profilePicUrl
) {
}
