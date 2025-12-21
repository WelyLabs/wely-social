package com.calendar.social.domain.models;

public record UserCreatedEventDTO (
        Long userId,
        String userName,
        String profilePicUrl
) {
}
