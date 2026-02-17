package com.calendar.social.infrastucture.persistence.mappers;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.infrastucture.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastucture.persistence.models.entities.UserNodeEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class UserNodeMapperTest {

    private final UserNodeMapper mapper = Mappers.getMapper(UserNodeMapper.class);

    @Test
    void toUserNodeEntity_nullSource_shouldReturnNull() {
        assertNull(mapper.toUserNodeEntity(null));
    }

    @Test
    void toUserNodeEntity_shouldMapSuccess() {
        UserCreatedEventDTO event = new UserCreatedEventDTO("id1", "user", 1234, "avatar");
        UserNodeEntity entity = mapper.toUserNodeEntity(event);

        assertNotNull(entity);
        assertEquals(event.userId(), entity.getUserId());
        assertEquals(event.userName(), entity.getUserName());
        assertEquals(event.hashtag(), entity.getHashtag());
        assertEquals(event.profilePicUrl(), entity.getProfilePicUrl());
    }

    @Test
    void toUserSocialDTO_nullSource_shouldReturnNull() {
        assertNull(mapper.toUserSocialDTO(null));
    }

    @Test
    void toUserSocialDTO_shouldMapSuccess() {
        UserSocialDBDTO dbDto = new UserSocialDBDTO("id1", "user", "avatar", "FRIENDS");
        UserSocialDTO dto = mapper.toUserSocialDTO(dbDto);

        assertNotNull(dto);
        assertEquals(dbDto.userId(), dto.userId());
        assertEquals(dbDto.userName(), dto.userName());
        assertEquals(dbDto.profilePicUrl(), dto.profilePicUrl());
        assertEquals(dbDto.relationStatus(), dto.relationStatus());
    }

    @Test
    void toUserNode_nullSource_shouldReturnNull() {
        assertNull(mapper.toUserNode(null));
    }

    @Test
    void toUserNode_shouldMapSuccess() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = mapper.toUserNode(entity);

        assertNotNull(dto);
        assertEquals(entity.getUserId(), dto.userId());
        assertEquals(entity.getUserName(), dto.userName());
        assertEquals(entity.getHashtag(), dto.hashtag());
        assertEquals(entity.getProfilePicUrl(), dto.profilePicUrl());
    }
}
