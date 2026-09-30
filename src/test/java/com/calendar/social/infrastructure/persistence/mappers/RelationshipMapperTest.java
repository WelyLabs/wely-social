package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers {@link RelationshipMapper#toRelationshipDTO}.
 *
 * <p>The source is the column projection the delete query returns, not a
 * {@code @RelationshipProperties} entity: Spring Data Neo4j could not map one of those from a
 * bare {@code RETURN}, which made removing a friend succeed and report a failure at once.
 */
class RelationshipMapperTest {

    private final RelationshipMapper mapper = Mappers.getMapper(RelationshipMapper.class);

    @Test
    @DisplayName("carries every field of an accepted, then deleted, relationship")
    void toRelationshipDTO_shouldMapEveryField() {
        RelationshipDBDTO deleted = new RelationshipDBDTO(
                "ACCEPTED", "2025-06-01T10:00:00", "2025-06-02T11:00:00", "2025-06-03T12:00:00");

        RelationshipDTO dto = mapper.toRelationshipDTO(deleted);

        assertNotNull(dto);
        assertEquals("ACCEPTED", dto.status());
        assertEquals("2025-06-01T10:00:00", dto.createdAt());
        assertEquals("2025-06-02T11:00:00", dto.acceptedAt());
        assertEquals("2025-06-03T12:00:00", dto.rejectedAt());
    }

    @Test
    @DisplayName("absent dates stay absent rather than becoming empty strings")
    void toRelationshipDTO_shouldPreserveNullDates() {
        // toString(null) in Cypher yields null, and a relationship that was never rejected has
        // no rejectedAt at all.
        RelationshipDBDTO deleted = new RelationshipDBDTO("PENDING", null, null, null);

        RelationshipDTO dto = mapper.toRelationshipDTO(deleted);

        assertNotNull(dto);
        assertEquals("PENDING", dto.status());
        assertNull(dto.createdAt());
        assertNull(dto.acceptedAt());
        assertNull(dto.rejectedAt());
    }

    @Test
    @DisplayName("null in, null out")
    void toRelationshipDTO_shouldMapNullToNull() {
        assertNull(mapper.toRelationshipDTO(null));
    }
}
