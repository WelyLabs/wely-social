package com.calendar.social.infrastructure.persistence.models.dtos;

/** One of a friend's friends, as the graph answers them: identity only, never closeness. */
public record FriendOfFriendDBDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl
) {}
