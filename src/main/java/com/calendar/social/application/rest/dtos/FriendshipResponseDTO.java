package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.Closeness;

/** A friendship seen from the caller. The version stays internal. */
public record FriendshipResponseDTO(
        String friendId,
        Closeness closeness,
        Closeness proposal,
        boolean proposedByFriend
) {}
