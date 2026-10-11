package com.calendar.social.infrastructure.persistence.models.dtos;

public record FriendSummaryDBDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String closeness,
        String proposal,
        Boolean proposedByFriend
) {}
