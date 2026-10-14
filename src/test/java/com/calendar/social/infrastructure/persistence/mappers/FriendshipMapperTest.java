package com.calendar.social.infrastructure.persistence.mappers;

import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendSummaryDBDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendshipDBDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipMapperTest {

    private final FriendshipMapper mapper = Mappers.getMapper(FriendshipMapper.class);

    @Test
    void toFriendship_shouldParseTheLevels() {
        Friendship friendship = mapper.toFriendship(new FriendshipDBDTO("bob", "CLOSE", "INTIMATE", true, 7L, null));

        assertThat(friendship).isEqualTo(new Friendship("bob", Closeness.CLOSE, Closeness.INTIMATE, true, 7, null));
    }

    @Test
    void toFriendship_shouldLeaveTheProposalNull_whenThereIsNone() {
        Friendship friendship = mapper.toFriendship(new FriendshipDBDTO("bob", "FRIEND", null, false, 0L, null));

        assertThat(friendship.proposal()).isNull();
    }

    @Test
    void toFriendSummary_shouldCarryTheUserAndTheLevels() {
        FriendSummary summary = mapper.toFriendSummary(
                new FriendSummaryDBDTO("bob", "bob", 2222, "/a.svg", "INTIMATE", null, false));

        assertThat(summary).isEqualTo(new FriendSummary("bob", "bob", 2222, "/a.svg", Closeness.INTIMATE, null, false));
    }

    @Test
    void toFriendship_shouldKeepTheFriendshipDate() {
        java.time.Instant since = java.time.Instant.parse("2025-01-02T03:04:05Z");

        assertThat(mapper.toFriendship(new FriendshipDBDTO("bob", "FRIEND", null, false, 0L, since)).since())
                .isEqualTo(since);
    }
}
