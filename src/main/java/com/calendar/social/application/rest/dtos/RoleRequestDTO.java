package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.CommunityRole;
import jakarta.validation.constraints.NotNull;

public record RoleRequestDTO(@NotNull CommunityRole role) {}
