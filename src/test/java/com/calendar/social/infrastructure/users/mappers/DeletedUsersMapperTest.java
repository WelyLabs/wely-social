package com.calendar.social.infrastructure.users.mappers;

import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.infrastructure.users.models.DeletedUserResponse;
import com.calendar.social.infrastructure.users.models.DeletedUsersPageResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeletedUsersMapperTest {

    private static final Instant AT = Instant.parse("2026-10-15T14:03:27.123Z");
    private static final UUID ID = UUID.fromString("3F0C2A4E-8B1D-4C6A-9E2F-1A2B3C4D5E6F");

    private final DeletedUsersMapper mapper = Mappers.getMapper(DeletedUsersMapper.class);

    @Test
    void toDomain_shouldWriteTheUserIdInItsCanonicalLowercaseForm() {
        assertThat(mapper.toDomain(new DeletedUserResponse(ID, AT)))
                .isEqualTo(new DeletedUser("3f0c2a4e-8b1d-4c6a-9e2f-1a2b3c4d5e6f", AT));
    }

    @Test
    void toDomain_shouldMapEveryItemAndTheCursor() {
        DeletedUsersPageResponse response = new DeletedUsersPageResponse(
                List.of(new DeletedUserResponse(ID, AT), new DeletedUserResponse(ID, null)), "cursor");

        assertThat(mapper.toDomain(response)).isEqualTo(new DeletedUsersPage(List.of(
                new DeletedUser(ID.toString(), AT), new DeletedUser(ID.toString(), null)), "cursor"));
    }

    @Test
    void toDomain_shouldBeNull_forNoPage() {
        assertThat(mapper.toDomain((DeletedUsersPageResponse) null)).isNull();
        assertThat(mapper.toDomain((DeletedUserResponse) null)).isNull();
    }
}
