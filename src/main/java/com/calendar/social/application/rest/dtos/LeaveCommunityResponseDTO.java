package com.calendar.social.application.rest.dtos;

/** @param newAdminId the member who became admin because the last admin left, or null */
public record LeaveCommunityResponseDTO(boolean communityDeleted, String newAdminId) {}
