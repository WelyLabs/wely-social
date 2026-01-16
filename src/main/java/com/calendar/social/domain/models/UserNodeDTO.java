package com.calendar.social.domain.models;

public record UserNodeDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) implements UserResult {
}
