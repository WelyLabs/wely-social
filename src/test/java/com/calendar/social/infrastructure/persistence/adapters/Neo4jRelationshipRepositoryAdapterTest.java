package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.RelationshipDTO;
import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.persistence.mappers.FriendshipMapper;
import com.calendar.social.infrastructure.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendSummaryDBDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendshipDBDTO;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.infrastructure.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastructure.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.UserNodeEntity;
import com.calendar.social.infrastructure.persistence.repositories.UserNodeRepository;
import com.calendar.social.infrastructure.persistence.models.dtos.FriendRequestDBDTO;
import com.calendar.social.domain.models.FriendRequest;
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
    private RelationshipMapper relationshipMapper;
    @Mock
    private UserNodeRepository userNodeRepository;
    @Mock
    private UserNodeMapper userNodeMapper;
    @Mock
    private FriendshipMapper friendshipMapper;

    private Neo4jRelationshipRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Neo4jRelationshipRepositoryAdapter(relationshipMapper, userNodeRepository, userNodeMapper, friendshipMapper);
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
        UserSocialDBDTO dbDto = new UserSocialDBDTO("id1", "user", 1234, "avatar", "FRIENDS", null);
        UserSocialDTO dto = new UserSocialDTO("id1", "user", 1234, "avatar", "FRIENDS", null);
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
        FriendRequestDBDTO row = new FriendRequestDBDTO("id1", "user", 1234, "avatar", "event-1");
        FriendRequest dto = new FriendRequest("id1", "user", 1234, "avatar", "event-1");
        when(userNodeRepository.findOutgoingRequests("userId")).thenReturn(Flux.just(row));
        when(userNodeMapper.toFriendRequest(row)).thenReturn(dto);

        StepVerifier.create(adapter.findOutgoingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void findIncomingRequests_shouldReturnMappedFlux() {
        FriendRequestDBDTO row = new FriendRequestDBDTO("id1", "user", 1234, "avatar", null);
        FriendRequest dto = new FriendRequest("id1", "user", 1234, "avatar", null);
        when(userNodeRepository.findIncomingRequests("userId")).thenReturn(Flux.just(row));
        when(userNodeMapper.toFriendRequest(row)).thenReturn(dto);

        StepVerifier.create(adapter.findIncomingRequests("userId"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void sendFriendRequest_Success() {
        UserNodeEntity entity = new UserNodeEntity("id1", "user", 1234, "avatar");
        UserNodeDTO dto = new UserNodeDTO("id1", "user", 1234, "avatar");
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234, "event-1")).thenReturn(Mono.just(entity));
        when(userNodeMapper.toUserNode(entity)).thenReturn(dto);

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234, "event-1"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void sendFriendRequest_shouldReturnErrorOnEmpty() {
        when(userNodeRepository.sendFriendRequest("u1", "u2", 1234, null)).thenReturn(Mono.empty());

        StepVerifier.create(adapter.sendFriendRequest("u1", "u2", 1234, null))
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
        // The query returns a column projection, not a relationship object: Spring Data Neo4j
        // cannot map a bare RETURN r onto a @RelationshipProperties class.
        RelationshipDBDTO deleted = new RelationshipDBDTO("ACCEPTED", null, null, null);
        RelationshipDTO dto = new RelationshipDTO("FRIENDS", null, null, null);
        when(userNodeRepository.deleteFriendship("u1", "u2")).thenReturn(Mono.just(deleted));
        when(relationshipMapper.toRelationshipDTO(deleted)).thenReturn(dto);

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectNext(dto)
                .verifyComplete();
    }


    @Test
    void deleteFriendship_shouldReturnErrorOnEmpty() {
        when(userNodeRepository.deleteFriendship("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteFriendship("u1", "u2"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void delete_shouldDetachDeleteTheUserNode() {
        when(userNodeRepository.deleteUser("id1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.delete("id1")).verifyComplete();

        org.mockito.Mockito.verify(userNodeRepository).deleteUser("id1");
    }

    @Test
    void findFriendship_shouldMapTheRow() {
        FriendshipDBDTO row = new FriendshipDBDTO("bob", "CLOSE", null, false, 2L);
        Friendship friendship = new Friendship("bob", Closeness.CLOSE, null, false, 2);
        when(userNodeRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(row));
        when(friendshipMapper.toFriendship(row)).thenReturn(friendship);

        StepVerifier.create(adapter.findFriendship("alice", "bob"))
                .expectNext(friendship)
                .verifyComplete();
    }

    @Test
    void findFriendSummaries_shouldMapEachRow() {
        FriendSummaryDBDTO row = new FriendSummaryDBDTO("bob", "bob", 2222, null, "FRIEND", null, false);
        FriendSummary summary = new FriendSummary("bob", "bob", 2222, null, Closeness.FRIEND, null, false);
        when(userNodeRepository.findFriendSummaries("alice")).thenReturn(Flux.just(row));
        when(friendshipMapper.toFriendSummary(row)).thenReturn(summary);

        StepVerifier.create(adapter.findFriendSummaries("alice"))
                .expectNext(summary)
                .verifyComplete();
    }

    @Test
    void updateCloseness_shouldStoreTheCallerAsProposer_whenTheProposalIsMine() {
        Friendship changed = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 1);
        when(userNodeRepository.updateCloseness("alice", "bob", "FRIEND", "CLOSE", "alice", 1L)).thenReturn(Mono.empty());

        StepVerifier.create(adapter.updateCloseness("alice", changed, 1)).verifyComplete();

        verify(userNodeRepository).updateCloseness("alice", "bob", "FRIEND", "CLOSE", "alice", 1L);
    }

    @Test
    void updateCloseness_shouldStoreTheFriendAsProposer_whenTheProposalIsTheirs() {
        Friendship changed = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, true, 1);
        when(userNodeRepository.updateCloseness(any(), any(), any(), any(), any(), anyLong())).thenReturn(Mono.empty());

        adapter.updateCloseness("alice", changed, 1).block();

        verify(userNodeRepository).updateCloseness("alice", "bob", "FRIEND", "CLOSE", "bob", 1L);
    }

    @Test
    void updateCloseness_shouldClearTheProposal_whenThereIsNone() {
        FriendshipDBDTO row = new FriendshipDBDTO("bob", "INTIMATE", null, false, 2L);
        Friendship changed = new Friendship("bob", Closeness.INTIMATE, null, false, 1);
        Friendship written = new Friendship("bob", Closeness.INTIMATE, null, false, 2);
        when(userNodeRepository.updateCloseness("alice", "bob", "INTIMATE", null, null, 1L)).thenReturn(Mono.just(row));
        when(friendshipMapper.toFriendship(row)).thenReturn(written);

        StepVerifier.create(adapter.updateCloseness("alice", changed, 1))
                .expectNext(written)
                .verifyComplete();
    }

    @Test
    void update_shouldUpsertTheNodeWithTheNewProfile() {
        UserModifiedEventDTO renamed = new UserModifiedEventDTO("id1", "camille_d", 4321, "Cam", "Dupont", "avatar");
        when(userNodeRepository.upsert("id1", "camille_d", 4321, "avatar"))
                .thenReturn(Mono.just(new UserNodeEntity("id1", "camille_d", 4321, "avatar")));

        StepVerifier.create(adapter.update(renamed)).verifyComplete();

        verify(userNodeRepository).upsert("id1", "camille_d", 4321, "avatar");
        verify(userNodeRepository, never()).save(any());
    }
}
