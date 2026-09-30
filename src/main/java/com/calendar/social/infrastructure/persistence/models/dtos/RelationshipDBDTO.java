package com.calendar.social.infrastructure.persistence.models.dtos;

/**
 * A relationship's properties, read as plain columns rather than as a relationship object.
 *
 * <p>{@code RelationshipEntity} is a {@code @RelationshipProperties} class with a
 * {@code @TargetNode}, which Spring Data Neo4j can only populate while walking a node's
 * relationships — never from a bare {@code RETURN r}. Returning one that way failed with
 * "Error mapping Record<{deletedRel: relationship}>", and because the DELETE had already run
 * server-side, removing a friend succeeded and answered 500 at the same time.
 *
 * <p>Dates are strings because they are read with {@code toString()} in Cypher: the values have
 * to be captured before the relationship is deleted, and a deleted relationship's properties
 * cannot be read afterwards.
 */
public record RelationshipDBDTO(
        String status,
        String createdAt,
        String acceptedAt,
        String rejectedAt
) {}
