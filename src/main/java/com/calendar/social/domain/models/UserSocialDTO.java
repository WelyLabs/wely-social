package com.calendar.social.domain.models;

public record UserSocialDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String relationStatus,
        /** The event a pending request was sent from; null otherwise. */
        String viaEventId
) implements UserResult {}
