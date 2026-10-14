package com.calendar.social.infrastructure.persistence.mappers;

import java.time.Instant;
import com.calendar.social.infrastructure.persistence.models.dtos.PersonDBDTO;
import com.calendar.social.domain.models.RelationStatus;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendRequest;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendRequestDBDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.UserNodeEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;


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
        UserSocialDBDTO dbDto = new UserSocialDBDTO("id1", "user", 1234, "avatar", "SENT_BY_THEM", "event-1");
        UserSocialDTO dto = mapper.toUserSocialDTO(dbDto);

        assertNotNull(dto);
        assertEquals(dbDto.userId(), dto.userId());
        assertEquals(dbDto.userName(), dto.userName());
        assertEquals(dbDto.profilePicUrl(), dto.profilePicUrl());
        assertEquals(dbDto.relationStatus(), dto.relationStatus());
        assertEquals(dbDto.hashtag(), dto.hashtag());
        assertEquals(dbDto.viaEventId(), dto.viaEventId());
    }

    @Test
    void toUserNode_nullSource_shouldReturnNull() {
        assertNull(mapper.toUserNode(null));
    }

    @Test
    void toUserNode_shouldMapSuccess() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = mapper.toUserNode(entity);

        assertNotNull(dto);
        assertEquals(entity.getUserId(), dto.userId());
        assertEquals(entity.getUserName(), dto.userName());
        assertEquals(entity.getHashtag(), dto.hashtag());
        assertEquals(entity.getProfilePicUrl(), dto.profilePicUrl());
    }

    @Test
    void toFriendRequest_nullSource_shouldReturnNull() {
        assertNull(mapper.toFriendRequest(null));
    }

    @Test
    void toFriendRequest_shouldKeepTheEvent() {
        FriendRequestDBDTO row = new FriendRequestDBDTO("id1", "user", 1234, "avatar", "event-1");

        assertEquals(new FriendRequest("id1", "user", 1234, "avatar", "event-1"), mapper.toFriendRequest(row));
    }

    @Test
    void toPerson_nullSource_shouldReturnNull() {
        assertNull(mapper.toPerson(null));
    }

    @Test
    void toPerson_shouldParseTheStatusAndLevels() {
        Instant since = Instant.parse("2025-01-02T03:04:05Z");
        PersonDBDTO row = new PersonDBDTO("id1", "user", 1234, "avatar", "FRIENDS", "CLOSE", "INTIMATE", true, since);

        assertEquals(new Person("id1", "user", 1234, "avatar", RelationStatus.FRIENDS, Closeness.CLOSE,
                Closeness.INTIMATE, true, since), mapper.toPerson(row));
    }

    @Test
    void toPerson_shouldLeaveTheLevelsNull_whenNotFriends() {
        PersonDBDTO row = new PersonDBDTO("id1", "user", 1234, null, "SENT_BY_ME", null, null, false, null);

        Person person = mapper.toPerson(row);

        assertEquals(RelationStatus.SENT_BY_ME, person.relationStatus());
        assertNull(person.closeness());
        assertNull(person.since());
    }
}
