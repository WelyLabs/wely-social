package com.calendar.social.domain.models;

public record UserCreatedEventDTO (
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) {
}
