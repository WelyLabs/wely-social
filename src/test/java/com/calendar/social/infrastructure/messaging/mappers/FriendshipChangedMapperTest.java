package com.calendar.social.infrastructure.messaging.mappers;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipChangedMapperTest {

    private final FriendshipChangedMapper mapper = Mappers.getMapper(FriendshipChangedMapper.class);

    @Test
    void toDto_shouldCarryTheTypeByName_andBothUsers() {
        FriendshipEvent event = new FriendshipEvent(FriendshipEventType.REQUESTED, "alice", "Alice", "bob");

        assertThat(mapper.toDto(event)).isEqualTo(new FriendshipChangedDTO("REQUESTED", "alice", "Alice", "bob"));
    }

    @Test
    void toDto_shouldMapAnAcceptance() {
        FriendshipEvent event = new FriendshipEvent(FriendshipEventType.ACCEPTED, "bob", "Bob", "alice");

        assertThat(mapper.toDto(event).type()).isEqualTo("ACCEPTED");
    }

    @Test
    void toDto_shouldBeNull_forNoEvent() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
