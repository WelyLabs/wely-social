package com.calendar.social.domain.models;

public record UserSocialDTO(
        String userId,
        String userName,
        String profilePicUrl,
        String relationStatus
) implements UserResult {}