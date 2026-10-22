package com.calendar.social.domain.services;

import com.calendar.social.domain.ports.RecordingSocialActivity;
import com.calendar.social.domain.models.UserModifiedEventDTO;
import com.calendar.social.domain.models.*;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.TransactionRunner;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipServiceTest {

    @Mock
    private RelationshipRepository relationshipRepository;

    @Mock
    private FriendshipEventOutbox outbox;

    @Mock
    private TransactionRunner transactions;

    private final RecordingSocialActivity activity = new RecordingSocialActivity();

    private RelationshipService relationshipService;

    private UserNodeDTO userNodeDTO;
    private UserSocialDTO userSocialDTO;
    private final FriendRequest friendRequest = new FriendRequest("u2", "target", 5678, null, "event-1");
    private UserCreatedEventDTO userCreatedEventDTO;

    @BeforeEach
    void setUp() {
        relationshipService = new RelationshipService(relationshipRepository, outbox, transactions, activity);
        userNodeDTO = new UserNodeDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");
        userSocialDTO = new UserSocialDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png", "FRIENDS", null);
        userCreatedEventDTO = new UserCreatedEventDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");
        // Runs the work as is; the tests that need to see the boundary replace it.
        lenient().when(transactions.inTransaction(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(outbox.append(any())).thenReturn(Mono.empty());
    }

    private static final TechnicalException DATABASE_DOWN = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);

    private static FriendshipEvent event(FriendshipEventType type, String from, String to) {
        return new FriendshipEvent(type, from, to, null);
    }

    private static FriendshipEvent event(FriendshipEventType type, String from, String to, Closeness previous) {
        return new FriendshipEvent(type, from, to, previous);
    }

    @Test
    void sendFriendRequest_shouldRecordTheRequest_fromTheSenderToTheRecipient() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        when(relationshipRepository.sendFriendRequest(anyString(), eq("target"), eq(5678), isNull()))
                .thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", null))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(relationshipRepository).sendFriendRequest("senderId", "target", 5678, null);
        verify(outbox).append(event(FriendshipEventType.REQUESTED, "senderId", userNodeDTO.userId()));
        assertThat(activity.facts()).containsExactly(RecordingSocialActivity.FRIEND_REQUEST_SENT);
    }

    @Test
    void sendFriendRequest_UserNotFound() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(false));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", null))
                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                        ((BusinessException) throwable).getErrorCode() == BusinessErrorCode.USER_DOES_NOT_EXIST)
                .verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).sendFriendRequest(anyString(), anyString(), anyInt(), any());
        verifyNoInteractions(outbox);
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    void sendFriendRequest_shouldRecordNothing_whenTheRequestCannotBeCreated() {
        BusinessException related = new BusinessException(BusinessErrorCode.SEND_FRIEND_REQUEST_FAILURE);
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        when(relationshipRepository.sendFriendRequest("senderId", "target", 5678, null)).thenReturn(Mono.error(related));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", null))
                .expectErrorMatches(e -> e == related)
                .verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    @DisplayName("a record that cannot be written fails the request, which its transaction then rolls back")
    void sendFriendRequest_shouldFail_whenTheRecordCannotBeWritten() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        when(relationshipRepository.sendFriendRequest("senderId", "target", 5678, null))
                .thenReturn(Mono.just(userNodeDTO));
        when(outbox.append(any())).thenReturn(Mono.error(DATABASE_DOWN));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", null))
                .expectErrorMatches(e -> e == DATABASE_DOWN)
                .verify(Duration.ofSeconds(10));

        // Rolled back: not a request sent.
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    @DisplayName("the request and its record are written inside the transaction, nothing outside it")
    void sendFriendRequest_shouldWriteOnlyInsideTheTransaction() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        // A runner that never runs the work: any write seen now would have happened outside it.
        when(transactions.inTransaction(any())).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", null)).expectComplete().verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).sendFriendRequest(anyString(), anyString(), anyInt(), any());
        verifyNoInteractions(outbox);
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    @DisplayName("an acceptance goes from the acceptor to the original requester")
    void acceptFriendRequest_shouldRecordTheAcceptance_fromTheAcceptorToTheRequester() {
        when(relationshipRepository.acceptFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.acceptFriendRequest("userId", "senderId"))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).append(event(FriendshipEventType.ACCEPTED, "userId", userNodeDTO.userId()));
        assertThat(activity.facts()).containsExactly(RecordingSocialActivity.FRIEND_REQUEST_ACCEPTED);
    }

    @Test
    void acceptFriendRequest_shouldRecordNothing_whenThereIsNothingToAccept() {
        BusinessException nothing = new BusinessException(BusinessErrorCode.ACCEPT_FRIEND_REQUEST_FAILURE);
        when(relationshipRepository.acceptFriendRequest("userId", "senderId")).thenReturn(Mono.error(nothing));

        StepVerifier.create(relationshipService.acceptFriendRequest("userId", "senderId"))
                .expectErrorMatches(e -> e == nothing)
                .verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    void acceptFriendRequest_shouldFail_whenTheRecordCannotBeWritten() {
        when(relationshipRepository.acceptFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));
        when(outbox.append(any())).thenReturn(Mono.error(DATABASE_DOWN));

        StepVerifier.create(relationshipService.acceptFriendRequest("userId", "senderId"))
                .expectErrorMatches(e -> e == DATABASE_DOWN)
                .verify(Duration.ofSeconds(10));

        assertThat(activity.facts()).isEmpty();
    }

    @Test
    void acceptFriendRequest_shouldWriteOnlyInsideTheTransaction() {
        when(transactions.inTransaction(any())).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.acceptFriendRequest("userId", "senderId")).expectComplete().verify(Duration.ofSeconds(10));

        verifyNoInteractions(relationshipRepository, outbox);
    }

    @Test
    void rejectFriendRequest_shouldRecordTheRejection_fromTheRejecterToTheRequester() {
        when(relationshipRepository.rejectFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.rejectFriendRequest("userId", "senderId"))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).append(event(FriendshipEventType.REJECTED, "userId", userNodeDTO.userId()));
        assertThat(activity.facts()).containsExactly(RecordingSocialActivity.FRIEND_REQUEST_DECLINED);
    }

    @Test
    void rejectFriendRequest_shouldRecordNothing_whenThereIsNothingToReject() {
        BusinessException nothing = new BusinessException(BusinessErrorCode.REJECT_FRIEND_REQUEST_FAILURE);
        when(relationshipRepository.rejectFriendRequest("userId", "senderId")).thenReturn(Mono.error(nothing));

        StepVerifier.create(relationshipService.rejectFriendRequest("userId", "senderId"))
                .expectErrorMatches(e -> e == nothing)
                .verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
        assertThat(activity.facts()).isEmpty();
    }

    @Test
    void rejectFriendRequest_shouldReportNothing_whenTheRecordCannotBeWritten() {
        when(relationshipRepository.rejectFriendRequest("userId", "senderId")).thenReturn(Mono.just(userNodeDTO));
        when(outbox.append(any())).thenReturn(Mono.error(DATABASE_DOWN));

        StepVerifier.create(relationshipService.rejectFriendRequest("userId", "senderId"))
                .expectErrorMatches(e -> e == DATABASE_DOWN)
                .verify(Duration.ofSeconds(10));

        assertThat(activity.facts()).isEmpty();
    }

    @Test
    void rejectFriendRequest_shouldWriteOnlyInsideTheTransaction() {
        when(transactions.inTransaction(any())).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.rejectFriendRequest("userId", "senderId")).expectComplete().verify(Duration.ofSeconds(10));

        verifyNoInteractions(relationshipRepository, outbox);
    }

    @Test
    void deleteFriendship_shouldRecordTheRemoval_fromTheOneWhoEndedIt_withTheClosenessItHad() {
        when(relationshipRepository.deleteFriendship("userId", "friendId"))
                .thenReturn(Mono.just(new RelationshipDTO("ACCEPTED", null, null, null, Closeness.CLOSE)));

        StepVerifier.create(relationshipService.deleteFriendship("userId", "friendId"))
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).append(event(FriendshipEventType.REMOVED, "userId", "friendId", Closeness.CLOSE));
    }

    @Test
    void deleteFriendship_shouldRecordNothing_whenTheyWereNotFriends() {
        BusinessException nothing = new BusinessException(BusinessErrorCode.DELETE_FRIENDSHIP_FAILURE);
        when(relationshipRepository.deleteFriendship("userId", "friendId")).thenReturn(Mono.error(nothing));

        StepVerifier.create(relationshipService.deleteFriendship("userId", "friendId"))
                .expectErrorMatches(e -> e == nothing)
                .verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
    }

    @Test
    void deleteFriendship_shouldFail_whenTheRecordCannotBeWritten() {
        when(relationshipRepository.deleteFriendship("userId", "friendId"))
                .thenReturn(Mono.just(new RelationshipDTO("ACCEPTED", null, null, null, Closeness.CLOSE)));
        when(outbox.append(any())).thenReturn(Mono.error(DATABASE_DOWN));

        StepVerifier.create(relationshipService.deleteFriendship("userId", "friendId"))
                .expectErrorMatches(e -> e == DATABASE_DOWN)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void deleteFriendship_shouldWriteOnlyInsideTheTransaction() {
        when(transactions.inTransaction(any())).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.deleteFriendship("userId", "friendId")).expectComplete().verify(Duration.ofSeconds(10));

        verifyNoInteractions(relationshipRepository, outbox);
    }

    @Test
    void writeUser_Success() {
        when(relationshipRepository.save(userCreatedEventDTO)).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.writeUser(userCreatedEventDTO))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void readAllWithSocialStatus_All() {
        when(relationshipRepository.findAllWithSocialStatus("userId")).thenReturn(Flux.just(userSocialDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", null))
                .expectNext(userSocialDTO)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void sendFriendRequest_shouldPassOnTheEvent_whenSentFromOne() {
        when(relationshipRepository.existsByUserNameAndHashtag("target", 5678)).thenReturn(Mono.just(true));
        when(relationshipRepository.sendFriendRequest("senderId", "target", 5678, "event-1"))
                .thenReturn(Mono.just(userNodeDTO));

        StepVerifier.create(relationshipService.sendFriendRequest("senderId", "target#5678", "event-1"))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void sendFriendRequest_shouldRefuseAMalformedTag() {
        StepVerifier.create(Mono.defer(() -> relationshipService.sendFriendRequest("senderId", "no-hashtag", "event-1")))
                .expectErrorMatches(throwable -> throwable instanceof BusinessException &&
                        ((BusinessException) throwable).getErrorCode() == BusinessErrorCode.INVALID_USER_TAG)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void readAllWithSocialStatus_PendingIncoming() {
        when(relationshipRepository.findIncomingRequests("userId")).thenReturn(Flux.just(friendRequest));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "PENDING_INCOMING"))
                .expectNext(friendRequest)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void readAllWithSocialStatus_PendingOutgoing() {
        when(relationshipRepository.findOutgoingRequests("userId")).thenReturn(Flux.just(friendRequest));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "PENDING_OUTGOING"))
                .expectNext(friendRequest)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void readAllWithSocialStatus_Friends() {
        when(relationshipRepository.findAllFriends("userId")).thenReturn(Flux.just(userNodeDTO));

        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "FRIENDS"))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void readAllWithSocialStatus_Default() {
        StepVerifier.create(relationshipService.readAllWithSocialStatus("userId", "UNKNOWN"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private static BusinessErrorCode codeOf(Throwable error) {
        return ((BusinessException) error).getErrorCode();
    }

    private final Friendship friends = new Friendship("bob", Closeness.FRIEND, null, false, 4, null);

    @Test
    void findFriendship_shouldReturnTheFriendship() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.findFriendship("alice", "bob"))
                .expectNext(friends)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findFriendship_shouldRefuse_whenNotFriends() {
        when(relationshipRepository.findFriendship("alice", "carol")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.findFriendship("alice", "carol"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_FRIENDS)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void listFriendships_shouldReturnTheSummaries() {
        FriendSummary bob = new FriendSummary("bob", "bob", 2222, null, Closeness.CLOSE, null, false);
        when(relationshipRepository.findFriendSummaries("alice")).thenReturn(Flux.just(bob));

        StepVerifier.create(relationshipService.listFriendships("alice"))
                .expectNext(bob)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void listFriendsOf_shouldAnswerTheCircleToACloseFriend() {
        FriendOfFriend carol = new FriendOfFriend("carol", "carol", 3333, null);
        when(relationshipRepository.findFriendship("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.CLOSE, null, false, 1, null)));
        when(relationshipRepository.findFriendsOf("bob", "alice")).thenReturn(Flux.just(carol));

        StepVerifier.create(relationshipService.listFriendsOf("alice", "bob"))
                .expectNext(carol)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void listFriendsOf_shouldAnswerTheCircleToAnIntimateFriend() {
        when(relationshipRepository.findFriendship("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.INTIMATE, null, false, 1, null)));
        when(relationshipRepository.findFriendsOf("bob", "alice")).thenReturn(Flux.empty());

        StepVerifier.create(relationshipService.listFriendsOf("alice", "bob"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void listFriendsOf_shouldRefuseAPlainFriend() {
        when(relationshipRepository.findFriendship("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.FRIEND, null, false, 1, null)));

        StepVerifier.create(relationshipService.listFriendsOf("alice", "bob"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_CLOSE_ENOUGH)
                .verify(Duration.ofSeconds(10));
        verify(relationshipRepository, never()).findFriendsOf(anyString(), anyString());
    }

    @Test
    void listFriendsOf_shouldRefuseAStranger() {
        when(relationshipRepository.findFriendship("alice", "carol")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.listFriendsOf("alice", "carol"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_FRIENDS)
                .verify(Duration.ofSeconds(10));
        verify(relationshipRepository, never()).findFriendsOf(anyString(), anyString());
    }

    @Test
    void listFriendsOf_shouldLetSomeoneReadTheirOwnCircleWithoutACheck() {
        when(relationshipRepository.findFriendsOf("alice", "alice")).thenReturn(Flux.empty());

        StepVerifier.create(relationshipService.listFriendsOf("alice", "alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(relationshipRepository, never()).findFriendship(anyString(), anyString());
    }

    @Test
    void requestCloseness_shouldWriteTheProposalAgainstTheVersionItWasDecidedOn() {
        Friendship proposal = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 4, null);
        Friendship written = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 5, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));
        when(relationshipRepository.updateCloseness("alice", proposal, 4)).thenReturn(Mono.just(written));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.CLOSE))
                .expectNext(written)
                .expectComplete().verify(Duration.ofSeconds(10));

        // A mere proposal changes no closeness yet: nothing to record.
        verifyNoInteractions(outbox);
    }

    @Test
    void requestCloseness_shouldNotWrite_whenNothingChanges() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.FRIEND))
                .expectNext(friends)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).updateCloseness(anyString(), any(), anyLong());
    }

    @Test
    void requestCloseness_shouldReportAConflict_whenTheFriendshipChangedMeanwhile() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));
        when(relationshipRepository.updateCloseness(eq("alice"), any(), eq(4L))).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.INTIMATE))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.CLOSENESS_CONFLICT)
                .verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("a one-sided step down takes effect at once, and is recorded")
    void requestCloseness_shouldRecordTheChange_whenAStepDownTakesEffect() {
        Friendship close = new Friendship("bob", Closeness.CLOSE, null, false, 4, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(close));
        when(relationshipRepository.updateCloseness("alice", friends, 4)).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.FRIEND))
                .expectNext(friends)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).append(event(FriendshipEventType.CLOSENESS_CHANGED, "alice", "bob", Closeness.CLOSE));
    }

    @Test
    void requestCloseness_shouldFail_whenTheRecordCannotBeWritten() {
        Friendship close = new Friendship("bob", Closeness.CLOSE, null, false, 4, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(close));
        when(relationshipRepository.updateCloseness("alice", friends, 4)).thenReturn(Mono.just(friends));
        when(outbox.append(any())).thenReturn(Mono.error(DATABASE_DOWN));

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.FRIEND))
                .expectErrorMatches(e -> e == DATABASE_DOWN)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void requestCloseness_shouldWriteOnlyInsideTheTransaction() {
        Friendship close = new Friendship("bob", Closeness.CLOSE, null, false, 4, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(close));
        when(transactions.inTransaction(any())).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.requestCloseness("alice", "bob", Closeness.FRIEND)).expectComplete().verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).updateCloseness(anyString(), any(), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    void requestCloseness_shouldRefuse_whenNotFriends() {
        when(relationshipRepository.findFriendship("alice", "carol")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.requestCloseness("alice", "carol", Closeness.CLOSE))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NOT_FRIENDS)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void acceptCloseness_shouldApplyTheFriendsProposal() {
        Friendship pending = new Friendship("bob", Closeness.FRIEND, Closeness.INTIMATE, true, 4, null);
        Friendship agreed = new Friendship("bob", Closeness.INTIMATE, null, false, 4, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(pending));
        when(relationshipRepository.updateCloseness("alice", agreed, 4)).thenReturn(Mono.just(agreed));

        StepVerifier.create(relationshipService.acceptCloseness("alice", "bob"))
                .expectNext(agreed)
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).append(event(FriendshipEventType.CLOSENESS_CHANGED, "alice", "bob", Closeness.FRIEND));
    }

    @Test
    void acceptCloseness_shouldRefuse_whenNothingWasProposed() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.acceptCloseness("alice", "bob"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NO_CLOSENESS_PROPOSAL)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void declineCloseness_shouldClearTheFriendsProposal() {
        Friendship pending = new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, true, 4, null);
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(pending));
        when(relationshipRepository.updateCloseness("alice", friends, 4)).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.declineCloseness("alice", "bob"))
                .expectNext(friends)
                .expectComplete().verify(Duration.ofSeconds(10));

        // The closeness stays what it was: nothing to record.
        verifyNoInteractions(outbox);
    }

    @Test
    void declineCloseness_shouldRefuse_whenNothingWasProposed() {
        when(relationshipRepository.findFriendship("alice", "bob")).thenReturn(Mono.just(friends));

        StepVerifier.create(relationshipService.declineCloseness("alice", "bob"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.NO_CLOSENESS_PROPOSAL)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void updateUser_ShouldUpdateTheUsersNode() {
        UserModifiedEventDTO renamed = new UserModifiedEventDTO("id1", "camille_d", 4321, "Cam", "Dupont", null);
        when(relationshipRepository.update(renamed)).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.updateUser(renamed)).expectComplete().verify(Duration.ofSeconds(10));

        verify(relationshipRepository).update(renamed);
    }

    @Test
    void findPerson_shouldReturnTheProfileSeenByTheCaller() {
        Person bob = new Person("bob", "bob", 2222, null, RelationStatus.FRIENDS, Closeness.CLOSE, null, false,
                java.time.Instant.parse("2025-01-02T03:04:05Z"));
        when(relationshipRepository.findPerson("alice", "bob")).thenReturn(Mono.just(bob));

        StepVerifier.create(relationshipService.findPerson("alice", "bob"))
                .expectNext(bob)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findPerson_shouldRefuse_whenTheUserIsUnknown() {
        when(relationshipRepository.findPerson("alice", "nobody")).thenReturn(Mono.empty());

        StepVerifier.create(relationshipService.findPerson("alice", "nobody"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.USER_DOES_NOT_EXIST)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void findMutualFriends_shouldAskForAtMostFifty() {
        when(relationshipRepository.existsById("bob")).thenReturn(Mono.just(true));
        when(relationshipRepository.findMutualFriends("alice", "bob", 50)).thenReturn(Flux.just(userNodeDTO));

        StepVerifier.create(relationshipService.findMutualFriends("alice", "bob"))
                .expectNext(userNodeDTO)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findMutualFriends_shouldRefuse_whenTheUserIsUnknown() {
        when(relationshipRepository.existsById("nobody")).thenReturn(Mono.just(false));

        StepVerifier.create(relationshipService.findMutualFriends("alice", "nobody"))
                .expectErrorMatches(e -> codeOf(e) == BusinessErrorCode.USER_DOES_NOT_EXIST)
                .verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).findMutualFriends(anyString(), anyString(), anyInt());
    }
}
