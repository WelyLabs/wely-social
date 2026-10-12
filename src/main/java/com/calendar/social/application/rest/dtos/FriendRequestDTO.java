package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.UserTag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FriendRequestDTO(
        @NotBlank
        @Pattern(regexp = UserTag.PATTERN, message = "Format invalide. Utilisez Nom#1234")
        String userTag,
        /** The event both took part in, when the request is sent from it; optional. */
        @Size(max = 64)
        String viaEventId
) {

    /** A request sent by tag alone, outside any event. */
    public FriendRequestDTO(String userTag) {
        this(userTag, null);
    }
}
