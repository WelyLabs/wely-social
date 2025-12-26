package com.calendar.social.domain.models;

public record UserNodeDTO(
        Long userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) implements UserResult {
}
