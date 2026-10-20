package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.TransactionRunner;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountErasureServiceTest {

    private static final String USER = "user-1";
    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:03:27.123Z");

    @Mock
    private DeletedUserMarkers markers;

    @Mock
    private CommunityRepository communityRepository;

    @Mock
    private CommunityService communityService;

    @Mock
    private RelationshipRepository relationshipRepository;

    @Mock
    private FriendshipEventOutbox outbox;

    @Mock
    private TransactionRunner transactions;

    private final AtomicInteger transactionsOpened = new AtomicInteger();

    private AccountErasureService service;

    @BeforeEach
    void setUp() {
        // Runs the work as is, counting the transactions it was given to.
        when(transactions.inTransaction(any())).thenAnswer(invocation -> {
            transactionsOpened.incrementAndGet();
            return invocation.getArgument(0);
        });
        when(markers.markDeleted(anyString(), any())).thenReturn(Mono.empty());
        when(markers.markErased(anyString(), any())).thenReturn(Mono.empty());
        when(communityRepository.findJoined(USER)).thenReturn(Flux.empty());
        when(communityRepository.deleteInvitationsSentBy(USER)).thenReturn(Mono.just(0L));
        when(communityRepository.deleteEmptyCommunitiesCreatedBy(USER)).thenReturn(Flux.empty());
        when(communityService.discardPictures(any())).thenReturn(Mono.empty());
        when(relationshipRepository.delete(USER)).thenReturn(Mono.empty());
        when(relationshipRepository.findFriendSummaries(USER)).thenReturn(Flux.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());
        service = new AccountErasureService(markers, communityRepository, communityService, relationshipRepository,
                outbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Community membership(String communityId, CommunityRole role) {
        return new Community(communityId, communityId, null, List.of(), CommunityVisibility.PUBLIC, NOW, 3, role,
                List.of(), 0, null);
    }

    @Test
    void erase_shouldMarkFirstAndSetErasedAtLast_whenTheUserExists() {
        Community club = membership("c1", CommunityRole.ADMIN);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(club));
        when(communityService.leaveOnErasure(USER, club)).thenReturn(Mono.just(CommunityLeave.handedOverTo("bob")));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(markers, communityRepository, communityService, relationshipRepository);
        order.verify(markers).markDeleted(USER, DELETED_AT);
        order.verify(communityRepository).findJoined(USER);
        order.verify(communityService).leaveOnErasure(USER, club);
        order.verify(communityRepository).deleteInvitationsSentBy(USER);
        order.verify(relationshipRepository).delete(USER);
        order.verify(communityRepository).deleteEmptyCommunitiesCreatedBy(USER);
        order.verify(markers).markErased(USER, NOW);
    }

    @Test
    @DisplayName("every step runs inside the one transaction: nothing is called outside the work it is given")
    void erase_shouldRunEveryStepInsideOneTransaction_whenCalled() {
        // A runner that never runs the work: any port call seen now would have happened outside it.
        doReturn(Mono.empty()).when(transactions).inTransaction(any());

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        verify(transactions).inTransaction(any());
        verifyNoInteractions(markers, communityRepository, communityService, relationshipRepository, outbox);
    }

    @Test
    void erase_shouldOpenExactlyOneTransaction_whenCalled() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(transactionsOpened).hasValue(1);
    }

    @Test
    void erase_shouldUseTheEventsDeletedAt_whenPresent() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        verify(markers).markDeleted(USER, DELETED_AT);
    }

    @Test
    @DisplayName("a record from an older producer has no deletedAt: the clock stands in for it")
    void erase_shouldFallBackToTheClock_whenDeletedAtIsNull() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, null))).expectComplete().verify(Duration.ofSeconds(10));

        verify(markers).markDeleted(USER, NOW);
    }

    @Test
    void erase_shouldHandOverEveryCommunityInOrder_whenTheUserBelongsToSeveral() {
        Community first = membership("c1", CommunityRole.ADMIN);
        Community second = membership("c2", CommunityRole.MEMBER);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(first, second));
        when(communityService.leaveOnErasure(USER, first)).thenReturn(Mono.just(CommunityLeave.deleted()));
        when(communityService.leaveOnErasure(USER, second)).thenReturn(Mono.just(CommunityLeave.left()));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(communityService, relationshipRepository);
        order.verify(communityService).leaveOnErasure(USER, first);
        order.verify(communityService).leaveOnErasure(USER, second);
        order.verify(relationshipRepository).delete(USER);
    }

    @Test
    @DisplayName("a replay finds no node any more, and still sweeps invitations and empty communities")
    void erase_shouldStillSweepInvitationsAndEmptyCommunities_whenTheNodeIsAlreadyGone() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        verify(communityService, never()).leaveOnErasure(anyString(), any());
        verify(communityRepository).deleteInvitationsSentBy(USER);
        verify(communityRepository).deleteEmptyCommunitiesCreatedBy(USER);
        verify(markers).markErased(USER, NOW);
    }

    @Test
    void erase_shouldNotSetErasedAt_whenDeletingTheNodeFails() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(relationshipRepository.delete(USER)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verify(communityRepository, never()).deleteEmptyCommunitiesCreatedBy(anyString());
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldPropagateTheError_whenTheMarkerCannotBeWritten() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(markers.markDeleted(USER, DELETED_AT)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verifyNoInteractions(communityRepository, communityService, relationshipRepository);
    }

    @Test
    void erase_shouldPropagateTheError_whenACommunityCannotBeHandedOver() {
        Community club = membership("c1", CommunityRole.ADMIN);
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(club));
        when(communityService.leaveOnErasure(USER, club)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).delete(anyString());
    }

    @Test
    void erase_shouldRefuseWithoutTouchingThePorts_whenTheUserIdIsBlank() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(" ", DELETED_AT)))
                .expectError(IllegalArgumentException.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(service.erase(new UserDeletedEventDTO(null, DELETED_AT)))
                .expectError(IllegalArgumentException.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(service.erase(null)).expectError(IllegalArgumentException.class).verify(Duration.ofSeconds(10));

        verifyNoInteractions(transactions, markers, communityRepository, communityService, relationshipRepository,
                outbox);
    }

    private static FriendSummary friend(String userId, Closeness closeness) {
        return new FriendSummary(userId, userId, 1111, null, closeness, null, false);
    }

    @Test
    @DisplayName("each former friendship is recorded as REMOVED by the user, with its closeness, after the delete")
    void erase_shouldRecordARemovalPerFriend_afterDeletingTheNode() {
        when(relationshipRepository.findFriendSummaries(USER)).thenReturn(Flux.just(friend("bob", Closeness.FRIEND), friend("carol", Closeness.INTIMATE)));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(markers, relationshipRepository, outbox, communityRepository);
        order.verify(relationshipRepository).findFriendSummaries(USER);
        order.verify(relationshipRepository).delete(USER);
        order.verify(outbox).append(new FriendshipEvent(FriendshipEventType.REMOVED, USER, "bob", Closeness.FRIEND));
        order.verify(outbox).append(new FriendshipEvent(FriendshipEventType.REMOVED, USER, "carol", Closeness.INTIMATE));
        order.verify(communityRepository).deleteEmptyCommunitiesCreatedBy(USER);
        order.verify(markers).markErased(USER, NOW);
    }

    @Test
    @DisplayName("a replay finds no friendship any more, so it records nothing")
    void erase_shouldRecordNothing_whenTheUserHasNoFriendLeft() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        verifyNoInteractions(outbox);
    }

    @Test
    void erase_shouldNotSetErasedAt_whenARemovalCannotBeRecorded() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(relationshipRepository.findFriendSummaries(USER)).thenReturn(Flux.just(friend("bob", Closeness.FRIEND)));
        when(outbox.append(any())).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldPropagateTheError_whenTheFriendsCannotBeRead() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(relationshipRepository.findFriendSummaries(USER)).thenReturn(Flux.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verify(relationshipRepository, never()).delete(anyString());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("the communities the erasure deleted lose their pictures once the transaction has committed")
    void erase_shouldDiscardThePicturesOfTheDeletedCommunities_afterTheTransaction() {
        Community alone = membership("c1", CommunityRole.ADMIN);
        Community shared = membership("c2", CommunityRole.ADMIN);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(alone, shared));
        when(communityService.leaveOnErasure(USER, alone)).thenReturn(Mono.just(CommunityLeave.deleted()));
        when(communityService.leaveOnErasure(USER, shared)).thenReturn(Mono.just(CommunityLeave.handedOverTo("bob")));
        when(communityRepository.deleteEmptyCommunitiesCreatedBy(USER)).thenReturn(Flux.just("c9"));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectComplete().verify(Duration.ofSeconds(10));

        InOrder order = inOrder(markers, communityService);
        order.verify(markers).markErased(USER, NOW);
        order.verify(communityService).discardPictures(List.of("c1", "c9"));
    }

    @Test
    void erase_shouldDiscardNothing_whenTheTransactionFails() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        Community alone = membership("c1", CommunityRole.ADMIN);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(alone));
        when(communityService.leaveOnErasure(USER, alone)).thenReturn(Mono.just(CommunityLeave.deleted()));
        when(markers.markErased(anyString(), any())).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).expectErrorSatisfies(error ->
                assertThat(error).isSameAs(failure)).verify(Duration.ofSeconds(10));

        verify(communityService, never()).discardPictures(any());
    }
}
