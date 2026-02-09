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
}
