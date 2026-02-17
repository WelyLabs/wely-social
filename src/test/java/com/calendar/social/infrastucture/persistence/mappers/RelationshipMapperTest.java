package com.calendar.social.infrastucture.persistence.mappers;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.infrastucture.persistence.models.entities.RelationshipEntity;
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
        assertNull(mapper.toRelationshipDTO(null));
    }
}
