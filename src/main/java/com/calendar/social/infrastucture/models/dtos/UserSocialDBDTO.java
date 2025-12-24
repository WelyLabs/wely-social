package com.calendar.social.infrastucture.models.dtos;

public record UserSocialDBDTO(
        Long id,
        String userName,
        String profilePicUrl,
        String relationStatus
) {}