package com.calendar.social.infrastucture.persistence.adapters;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastucture.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastucture.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastucture.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastucture.persistence.models.entities.RelationshipEntity;
import com.calendar.social.infrastucture.persistence.models.entities.UserNodeEntity;
import com.calendar.social.infrastucture.persistence.repositories.UserNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Neo4jRelationshipRepositoryAdapterTest {

    @Mock
    private com.calendar.social.infrastucture.persistence.repositories.RelationshipRepository relationshipRepository;
    @Mock
    private RelationshipMapper relationshipMapper;
    @Mock
    private UserNodeRepository userNodeRepository;
    @Mock
    private UserNodeMapper userNodeMapper;

    private Neo4jRelationshipRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Neo4jRelationshipRepositoryAdapter(relationshipRepository, relationshipMapper, userNodeRepository,
                userNodeMapper);
    }

    @Test
    void save_shouldCallRepository() {
        UserCreatedEventDTO event = new UserCreatedEventDTO("id1", "user", 1234, "avatar");
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        when(userNodeMapper.toUserNodeEntity(event)).thenReturn(entity);
        when(userNodeRepository.save(entity)).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.save(event))
                .verifyComplete();
    }

    @Test
    void save_shouldMapError() {
        UserCreatedEventDTO event = new UserCreatedEventDTO("id1", "user", 1234, "avatar");
        when(userNodeMapper.toUserNodeEntity(event))
                .thenReturn(new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList()));
        when(userNodeRepository.save(any())).thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.save(event))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void findAllWithSocialStatus_shouldReturnMappedFlux() {
        UserSocialDBDTO dbDto = new UserSocialDBDTO("id1", "user", "avatar", "FRIENDS");
        UserSocialDTO dto = new UserSocialDTO("id1", "user", "avatar", "FRIENDS");
        when(userNodeRepository.findAllWithSocialStatus("userId")).thenReturn(Flux.just(dbDto));
        when(userNodeMapper.toUserSocialDTO(dbDto)).thenReturn(dto);

        StepVerifier.create(adapter.findAllWithSocialStatus("userId"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void findAllWithSocialStatus_shouldMapError() {
        when(userNodeRepository.findAllWithSocialStatus("userId"))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.findAllWithSocialStatus("userId"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void findAllFriends_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findAllFriends("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findAllFriends("userId"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void findAllFriends_shouldMapError() {
        when(userNodeRepository.findAllFriends("userId")).thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.findAllFriends("userId"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void existsByUserNameAndHashtag_shouldReturnBoolean() {
        when(userNodeRepository.existsByUserNameAndHashtag("user", 1234)).thenReturn(Mono.just(true));

        StepVerifier.create(adapter.existsByUserNameAndHashtag("user", 1234))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsByUserNameAndHashtag_shouldMapError() {
        when(userNodeRepository.existsByUserNameAndHashtag("user", 1234))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.existsByUserNameAndHashtag("user", 1234))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void findOutgoingRequests_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findOutgoingRequests("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findOutgoingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void findOutgoingRequests_shouldMapError() {
        when(userNodeRepository.findOutgoingRequests("userId"))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.findOutgoingRequests("userId"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void findIncomingRequests_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findIncomingRequests("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findIncomingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void findIncomingRequests_shouldMapError() {
        when(userNodeRepository.findIncomingRequests("userId"))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.findIncomingRequests("userId"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void sendFriendRequest_Success() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234)).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void sendFriendRequest_shouldMapError() {
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void sendFriendRequest_shouldReturnErrorOnEmpty() {
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234)).thenReturn(Mono.empty());

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void acceptFriendRequest_Success() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.acceptFriendRequest("u1", "u2")).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.acceptFriendRequest("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void acceptFriendRequest_shouldMapError() {
        when(userNodeRepository.acceptFriendRequest("u1", "u2"))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.acceptFriendRequest("u1", "u2"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void acceptFriendRequest_shouldReturnErrorOnEmpty() {
        when(userNodeRepository.acceptFriendRequest("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.acceptFriendRequest("u1", "u2"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void rejectFriendRequest_Success() {
        UserNodeEntity entity = new UserNodeEntity("uuid", "id1", "user", 1234, "avatar", Collections.emptyList());
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.rejectFriendRequest("u1", "u2")).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.rejectFriendRequest("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void rejectFriendRequest_shouldMapError() {
        when(userNodeRepository.rejectFriendRequest("u1", "u2"))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.rejectFriendRequest("u1", "u2"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void rejectFriendRequest_shouldReturnErrorOnEmpty() {
        when(userNodeRepository.rejectFriendRequest("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.rejectFriendRequest("u1", "u2"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void deleteFriendship_Success() {
        RelationshipEntity entity = new RelationshipEntity();
        RelationshipDTO dto = new RelationshipDTO("FRIENDS", null, null, null);
        when(relationshipRepository.deleteFriendship("u1", "u2")).thenReturn(Mono.just(entity));
        when(relationshipMapper.toRelationshipDTO(entity)).thenReturn(dto);

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
    }

    @Test
    void deleteFriendship_shouldMapError() {
        when(relationshipRepository.deleteFriendship("u1", "u2"))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void deleteFriendship_shouldReturnErrorOnEmpty() {
        when(relationshipRepository.deleteFriendship("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectError(BusinessException.class)
                .verify();
    }
}
