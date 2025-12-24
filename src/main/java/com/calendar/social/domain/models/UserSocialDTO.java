package com.calendar.social.domain.models;

public record UserSocialDTO(
        Long id,
        String userName,
        String profilePicUrl,
        String relationStatus
) {}