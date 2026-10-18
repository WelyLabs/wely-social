package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipChangedMapperTest {

    private static final ZonedDateTime SINCE = ZonedDateTime.parse("2026-10-01T10:00:00+02:00[Europe/Paris]");
    private static final ZonedDateTime AT = ZonedDateTime.parse("2026-10-18T09:30:00.123Z");

    private final FriendshipChangedMapper mapper = Mappers.getMapper(FriendshipChangedMapper.class);

    private static FriendshipOutboxEventEntity record(String type, String closeness, ZonedDateTime since) {
        return new FriendshipOutboxEventEntity(1760779800123L, type, "bob", "Bob", "alice", "alice:bob", closeness,
                null, since, AT, null, 0L, null, null);
    }

    @Test
    void toDto_shouldCarryEveryFieldOfTheRecord_withInstantsInUtc() {
        assertThat(mapper.toDto(record("ACCEPTED", "FRIEND", SINCE))).isEqualTo(new FriendshipChangedDTO(
                "ACCEPTED", "bob", "Bob", "alice", "FRIEND", null, Instant.parse("2026-10-01T08:00:00Z"),
                1760779800123L, Instant.parse("2026-10-18T09:30:00.123Z")));
    }

    @Test
    void toDto_shouldLeaveClosenessAndSinceNull_whenTheTwoAreNotFriends() {
        FriendshipChangedDTO dto = mapper.toDto(record("REMOVED", null, null));

        assertThat(dto.closeness()).isNull();
        assertThat(dto.since()).isNull();
    }

    @Test
    void toDto_shouldBeNull_forNoRecord() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toInstant_shouldConvertAZonedDateTime_andKeepNull() {
        assertThat(mapper.toInstant(SINCE.withZoneSameInstant(ZoneId.of("UTC"))))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(mapper.toInstant(null)).isNull();
    }
}
