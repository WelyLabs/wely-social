package com.calendar.social.infrastructure.persistence.models.dtos;

public record UserSocialDBDTO(
        String userId,
        String userName,
        String profilePicUrl,
        String relationStatus
) {}