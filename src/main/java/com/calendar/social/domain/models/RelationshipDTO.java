package com.calendar.social.domain.models;

public record RelationshipDTO(
        String status,
        String createdAt,
        String acceptedAt,
        String rejectedAt,
        Closeness closeness
) {
}
