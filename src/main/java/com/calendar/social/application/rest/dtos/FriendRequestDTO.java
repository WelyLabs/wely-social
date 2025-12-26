package com.calendar.social.application.rest.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FriendRequestDTO(
        @NotBlank
        @Pattern(regexp = "^.+#\\d{4,6}$", message = "Format invalide. Utilisez Nom#1234")
        String userTag
) {}
