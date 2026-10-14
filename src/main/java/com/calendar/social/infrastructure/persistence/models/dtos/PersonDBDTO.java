package com.calendar.social.infrastructure.persistence.models.dtos;

import java.time.Instant;

/** Column projection of another user and the caller's relationship to them; see findPerson. */
public record PersonDBDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        String relationStatus,
        String closeness,
        String proposal,
        Boolean proposedByFriend,
        Instant since
) {}
