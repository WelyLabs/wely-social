package com.calendar.social.application.rest.mappers;

import com.calendar.social.application.rest.dtos.FriendshipResponseDTO;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.Friendship;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipRestMapperTest {

    private final FriendshipRestMapper mapper = Mappers.getMapper(FriendshipRestMapper.class);

    @Test
    void toResponse_shouldLeaveTheVersionOut() {
        FriendshipResponseDTO response = mapper.toResponse(new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, true, 9));

        assertThat(response).isEqualTo(new FriendshipResponseDTO("bob", Closeness.FRIEND, Closeness.CLOSE, true));
    }
}
