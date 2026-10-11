package com.calendar.social.infrastructure.persistence.models.dtos;

/** A friendship's closeness columns, read relative to the caller; see the queries in UserNodeRepository. */
public record FriendshipDBDTO(
        String friendId,
        String closeness,
        String proposal,
        Boolean proposedByFriend,
        Long version
) {}
