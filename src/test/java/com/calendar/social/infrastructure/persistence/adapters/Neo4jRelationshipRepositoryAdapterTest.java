package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastructure.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastructure.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.RelationshipEntity;
import com.calendar.social.infrastructure.persistence.models.entities.UserNodeEntity;
import com.calendar.social.infrastructure.persistence.repositories.RelationshipNeo4jRepository;
import com.calendar.social.infrastructure.persistence.repositories.UserNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ten {@code *_shouldMapError} cases that used to live here are gone: translating an
 * infrastructure failure into {@code TechnicalException(DATABASE_ERROR)} moved out of this
 * class and into {@code PersistenceErrorAspect}. That is the trade-off of an aspect — the
 * behaviour is no longer reachable from a plain unit test of the adapter, because it is
 * the Spring proxy that carries it.
 *
 * <p>The coverage moved with it, to {@code PersistenceErrorAspectTest} for the logic and
 * {@code PersistenceErrorAspectWiringTest} for proof that the pointcut actually matches
 * these methods.
 */
@ExtendWith(MockitoExtension.class)
class Neo4jRelationshipRepositoryAdapterTest {

    @Mock
    private RelationshipNeo4jRepository relationshipRepository;
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
    void save_shouldUpsertByUserIdRatherThanInsert() {
        UserCreatedEventDTO event = new UserCreatedEventDTO("id1", "user", 1234, "avatar");
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        when(userNodeRepository.upsert("id1", "user", 1234, "avatar")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.save(event))
                .verifyComplete();

        verify(userNodeRepository).upsert("id1", "user", 1234, "avatar");
        // save() would add another node on every replay of USER_CREATED.
        verify(userNodeRepository, never()).save(any());
    }

    @Test
    void save_shouldBeIdempotentAcrossReplays() {
        UserCreatedEventDTO event = new UserCreatedEventDTO("id1", "user", 1234, "avatar");
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        when(userNodeRepository.upsert("id1", "user", 1234, "avatar")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.save(event)).verifyComplete();
        StepVerifier.create(adapter.save(event)).verifyComplete();

        verify(userNodeRepository, times(2)).upsert("id1", "user", 1234, "avatar");
        verify(userNodeRepository, never()).save(any());
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
    void findAllFriends_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findAllFriends("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findAllFriends("userId"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void existsByUserNameAndHashtag_shouldReturnBoolean() {
        when(userNodeRepository.existsByUserNameAndHashtag("user", 1234)).thenReturn(Mono.just(true));

        StepVerifier.create(adapter.existsByUserNameAndHashtag("user", 1234))
                .expectNext(true)
                .verifyComplete();
    }


    @Test
    void findOutgoingRequests_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findOutgoingRequests("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findOutgoingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void findIncomingRequests_shouldReturnMappedFlux() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.findIncomingRequests("userId")).thenReturn(Flux.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.findIncomingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void sendFriendRequest_Success() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234)).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234))
                .expectNext(dto)
                .verifyComplete();
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
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.acceptFriendRequest("u1", "u2")).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.acceptFriendRequest("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
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
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.rejectFriendRequest("u1", "u2")).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.rejectFriendRequest("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
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
    void deleteFriendship_shouldReturnErrorOnEmpty() {
        when(relationshipRepository.deleteFriendship("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectError(BusinessException.class)
                .verify();
    }
}
