package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.RelationshipEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipMapperTest {

    private final RelationshipMapper mapper = Mappers.getMapper(RelationshipMapper.class);

    @Test
    void toRelationshipDTO_shouldMapSuccess() {
        LocalDateTime now = LocalDateTime.now();
        RelationshipEntity entity = new RelationshipEntity(UUID.randomUUID().toString(), "FRIENDS", now, now, now,
                null);
        RelationshipDTO dto = mapper.toRelationshipDTO(entity);

        assertNotNull(dto);
        assertEquals(entity.getStatus(), dto.status());
        assertNotNull(dto.createdAt());
        assertNotNull(dto.acceptedAt());
        assertNotNull(dto.rejectedAt());
    }

    @Test
    void toRelationshipDTO_withNullDates_shouldMapSuccess() {
        RelationshipEntity entity = new RelationshipEntity(UUID.randomUUID().toString(), "PENDING", null, null, null,
                null);
        RelationshipDTO dto = mapper.toRelationshipDTO(entity);

        assertNotNull(dto);
        assertEquals("PENDING", dto.status());
        assertNull(dto.createdAt());
        assertNull(dto.acceptedAt());
        assertNull(dto.rejectedAt());
    }

    @Test
    void toRelationshipDTO_nullSource_shouldReturnNull() {
        // Both overloads, named explicitly: the mapper now also maps the column projection the
        // delete query returns, so a bare null no longer picks one on its own.
        assertNull(mapper.toRelationshipDTO((RelationshipEntity) null));
        assertNull(mapper.toRelationshipDTO((RelationshipDBDTO) null));
    }

    @Test
    void toRelationshipDTO_shouldMapTheDeleteProjection() {
        RelationshipDBDTO deleted =
                new RelationshipDBDTO("ACCEPTED", "2025-06-01T10:00:00", "2025-06-02T10:00:00", null);

        RelationshipDTO dto = mapper.toRelationshipDTO(deleted);

        assertEquals("ACCEPTED", dto.status());
        assertEquals("2025-06-01T10:00:00", dto.createdAt());
        assertEquals("2025-06-02T10:00:00", dto.acceptedAt());
        assertNull(dto.rejectedAt());
    }
}
