package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.RelationStatus;

import java.time.Instant;

/** A user's profile as the caller sees it; closeness, proposal and since are null unless friends. */
public record PersonResponseDTO(
        String userId,
        String userName,
        Integer hashtag,
        String profilePicUrl,
        RelationStatus relationStatus,
        Closeness closeness,
        Closeness proposal,
        boolean proposedByFriend,
        Instant since
) {}
