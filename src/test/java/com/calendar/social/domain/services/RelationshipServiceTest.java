package com.calendar.social.domain.services;

import com.calendar.social.domain.models.*;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipServiceTest {

    @Mock
    private RelationshipRepository relationshipRepository;

    @InjectMocks
    private RelationshipService relationshipService;

    private UserNodeDTO userNodeDTO;
    private UserSocialDTO userSocialDTO;
    private UserCreatedEventDTO userCreatedEventDTO;

    @BeforeEach
    void setUp() {
        userNodeDTO = new UserNodeDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");
        userSocialDTO = new UserSocialDTO(UUID.randomUUID().toString(), "testuser", "avatar.png", "FRIENDS");
        userCreatedEventDTO = new UserCreatedEventDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");
    }

    @Test
    void sendFriendRequest_Success() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        when(relationshipRepository.sendFriendRequest(anyString(), eq("target"), eq(5678)))
                .thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678"))
                .expectNext(userNodeDTO)
                .verifyComplete();

        verify(relationshipRepository).existsByUserNameAndHashtag("target", 5678);
        verify(relationshipRepository).sendFriendRequest("senderId", "target", 5678);
    }

    @Test
    void sendFriendRequest_UserNotFound() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(false));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678"))
                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                        ((BusinessException) throwable).getErrorCode() == BusinessErrorCode.USER_DOES_NOT_EXIST)
                .verify();

        verify(relationshipRepository).existsByUserNameAndHashtag("target", 5678);
        verify(relationshipRepository, never()).sendFriendRequest(anyString(), anyString(), anyInt());
    }

    @Test
    void acceptFriendRequest_Success() {
        when(relationshipRepository.acceptFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.acceptFriendRequest("userId", "senderId"))
                .expectNext(userNodeDTO)
                .verifyComplete();
    }

    @Test
    void rejectFriendRequest_Success() {
        when(relationshipRepository.rejectFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.rejectFriendRequest("userId", "senderId"))
                .expectNext(userNodeDTO)
                .verifyComplete();
    }

    @Test
    void deleteFriendship_Success() {
        when(relationshipRepository.deleteFriendship("userId", "friendId"))
                .thenReturn(Mono.just(new RelationshipDTO(UUID.randomUUID().toString(), "USER", "FRIEND", "FRIENDS")));

        StepVerifier.create(relationshipService.deleteFriendship("userId", "friendId"))
                .verifyComplete();
    }

    @Test
    void writeUser_Success() {
        when(relationshipRepository.save(userCreatedEventDTO)).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.writeUser(userCreatedEventDTO))
                .verifyComplete();
    }

    @Test
    void readAllWithSocialStatus_All() {
        when(relationshipRepository.findAllWithSocialStatus("userId")).thenReturn(Flux.just(userSocialDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", null))
                .expectNext(userSocialDTO)
                .verifyComplete();
    }

    @Test
    void readAllWithSocialStatus_PendingIncoming() {
        when(relationshipRepository.findIncomingRequests("userId")).thenReturn(Flux.just(userNodeDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "PENDING_INCOMING"))
                .expectNext(userNodeDTO)
                .verifyComplete();
    }

    @Test
    void readAllWithSocialStatus_PendingOutgoing() {
        when(relationshipRepository.findOutgoingRequests("userId")).thenReturn(Flux.just(userNodeDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "PENDING_OUTGOING"))
                .expectNext(userNodeDTO)
                .verifyComplete();
    }

    @Test
    void readAllWithSocialStatus_Friends() {
        when(relationshipRepository.findAllFriends("userId")).thenReturn(Flux.just(userNodeDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "FRIENDS"))
                .expectNext(userNodeDTO)
                .verifyComplete();
    }

    @Test
    void readAllWithSocialStatus_Default() {
        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "UNKNOWN"))
                .verifyComplete();
    }

    @Test
    void removeUser_ShouldDeleteTheUsersNode() {
        when(relationshipRepository.delete("orphan-id")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.removeUser(new UserDeletedEventDTO("orphan-id")))
                .verifyComplete();

        verify(relationshipRepository).delete("orphan-id");
    }

    private static BusinessErrorCode codeOf(Throwable error) {
        return ((BusinessException) error).getErrorCode();
    }

    private final Friendship friends = new Friendship("bob", Closeness.FRIEND, null, false, 4);

    @Test
    void findFriendship_shouldReturnTheFriendship() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.findFriendship("alice", "bob"))
                .expectNext(friends)
                .verifyComplete();
    }

    @Test
    void findFriendship_shouldRefuse_whenNotFriends() {
        when(relationshipRepository.findFriendship("alice", "carol")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.findFriendship("alice", "carol"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_FRIENDS)
                .verify();
    }

    @Test
    void listFriendships_shouldReturnTheSummaries() {
        FriendSummary bob = new FriendSummary("bob", "bob", 2222, null, Closeness.CLOSE, null, false);
        when(relationshipRepository.findFriendSummaries("alice")).thenReturn(Flux.just(bob));

        StepVerifier.create(relationshipService.listFriendships("alice"))
                .expectNext(bob)
                .verifyComplete();
    }

    @Test
    void requestCloseness_shouldWriteTheProposalAgainstTheVersionItWasDecidedOn() {
        Friendship proposal = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 4);
        Friendship written = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 5);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));
        when(relationshipRepository.updateCloseness("alice", proposal, 4)).thenReturn(Mono.just(written));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.CLOSE))
                .expectNext(written)
                .verifyComplete();
    }

    @Test
    void requestCloseness_shouldNotWrite_whenNothingChanges() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.FRIEND))
                .expectNext(friends)
                .verifyComplete();

        verify(relationshipRepository, never()).updateCloseness(anyString(), any(), anyLong());
    }

    @Test
    void requestCloseness_shouldReportAConflict_whenTheFriendshipChangedMeanwhile() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));
        when(relationshipRepository.updateCloseness(eq("alice"), any(), eq(4L))).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.INTIMATE))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.CLOSENESS_CONFLICT)
                .verify();
    }

    @Test
    void requestCloseness_shouldRefuse_whenNotFriends() {
        when(relationshipRepository.findFriendship("alice", "carol")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.requestCloseness("alice", "carol", Closeness.CLOSE))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_FRIENDS)
                .verify();
    }

    @Test
    void acceptCloseness_shouldApplyTheFriendsProposal() {
        Friendship pending = new Friendship("bob", Closeness.FRIEND, Closeness.INTIMATE, true, 4);
        Friendship agreed = new Friendship("bob", Closeness.INTIMATE, null, false, 4);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(pending));
        when(relationshipRepository.updateCloseness("alice", agreed, 4)).thenReturn(Mono.just(agreed));

        StepVerifier.create(relationshipService.acceptCloseness("alice", "bob"))
                .expectNext(agreed)
                .verifyComplete();
    }

    @Test
    void acceptCloseness_shouldRefuse_whenNothingWasProposed() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.acceptCloseness("alice", "bob"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NO_CLOSENESS_PROPOSAL)
                .verify();
    }

    @Test
    void declineCloseness_shouldClearTheFriendsProposal() {
        Friendship pending = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, true, 4);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(pending));
        when(relationshipRepository.updateCloseness("alice", friends, 4)).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.declineCloseness("alice", "bob"))
                .expectNext(friends)
                .verifyComplete();
    }

    @Test
    void declineCloseness_shouldRefuse_whenNothingWasProposed() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.declineCloseness("alice", "bob"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NO_CLOSENESS_PROPOSAL)
                .verify();
    }
}
