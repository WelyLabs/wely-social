package com.calendar.social.infrastructure.persistence.models.dtos;

public record UserSocialDBDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String relationStatus,
        String viaEventId
) {}
