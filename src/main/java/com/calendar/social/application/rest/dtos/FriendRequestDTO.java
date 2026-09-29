package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.UserTag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FriendRequestDTO(
        @NotBlank
        @Pattern(regexp = UserTag.PATTERN, message = "Format invalide. Utilisez Nom#1234")
        String userTag
) {}
