package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.Closeness;
import jakarta.validation.constraints.NotNull;

public record ClosenessRequestDTO(@NotNull Closeness closeness) {}
