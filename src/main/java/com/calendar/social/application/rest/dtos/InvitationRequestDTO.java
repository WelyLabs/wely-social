package com.calendar.social.application.rest.dtos;

import jakarta.validation.constraints.NotBlank;

/** The friend to invite; the link then goes to them by message. */
public record InvitationRequestDTO(@NotBlank String inviteeId) {}
