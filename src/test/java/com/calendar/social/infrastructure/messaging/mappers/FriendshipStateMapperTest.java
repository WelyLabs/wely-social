package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.infrastructure.messaging.models.FriendshipStateDTO;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipStateMapperTest {

    private static final ZonedDateTime SINCE = ZonedDateTime.parse("2026-10-01T10:00:00+02:00[Europe/Paris]");

    private final FriendshipStateMapper mapper = Mappers.getMapper(FriendshipStateMapper.class);

    private static FriendshipOutboxEventEntity record(String fromUserId, String toUserId) {
        FriendshipOutboxEventEntity record = new FriendshipOutboxEventEntity();
        record.setVersion(1760779800123L);
        record.setType("CLOSENESS_CHANGED");
        record.setFromUserId(fromUserId);
        record.setFromUserName("someone");
        record.setToUserId(toUserId);
        record.setCloseness("CLOSE");
        record.setPreviousCloseness("FRIEND");
        record.setSince(SINCE);
        return record;
    }

    @Test
    void toDto_shouldCarryTheFriendship_withSortedIds_andSinceInUtc() {
        assertThat(mapper.toDto(record("bob", "alice"))).isEqualTo(new FriendshipStateDTO("alice", "bob", true,
                "CLOSE", Instant.parse("2026-10-01T08:00:00Z"), 1760779800123L));
    }

    @Test
    void toDto_shouldGiveTheSameIds_whicheverUserActed() {
        FriendshipStateDTO fromBob = mapper.toDto(record("bob", "alice"));
        FriendshipStateDTO fromAlice = mapper.toDto(record("alice", "bob"));

        assertThat(fromAlice).isEqualTo(fromBob);
    }

    @Test
    void toDto_shouldLeaveSinceNull_whenTheRecordHasNone() {
        FriendshipOutboxEventEntity record = record("bob", "alice");
        record.setSince(null);

        assertThat(mapper.toDto(record).since()).isNull();
    }

    @Test
    void toInstant_shouldBeNull_forANullDate() {
        assertThat(mapper.toInstant(null)).isNull();
    }

    @Test
    void lowerId_shouldBeTheSmallerId_inEitherOrder() {
        assertThat(FriendshipStateMapper.lowerId(record("bob", "alice"))).isEqualTo("alice");
        assertThat(FriendshipStateMapper.lowerId(record("alice", "bob"))).isEqualTo("alice");
    }

    @Test
    void higherId_shouldBeTheGreaterId_inEitherOrder() {
        assertThat(FriendshipStateMapper.higherId(record("bob", "alice"))).isEqualTo("bob");
        assertThat(FriendshipStateMapper.higherId(record("alice", "bob"))).isEqualTo("bob");
    }

    @Test
    void stateKey_shouldJoinTheSortedIds_likeTheChangeTopicsPairKey() {
        assertThat(FriendshipStateMapper.stateKey(record("bob", "alice"))).isEqualTo("alice:bob");
        assertThat(FriendshipStateMapper.stateKey(record("alice", "bob"))).isEqualTo("alice:bob");
    }
}
