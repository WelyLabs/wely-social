package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
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
        when(communityRepository.deleteEmptyCommunitiesCreatedBy(USER)).thenReturn(Mono.just(0L));
        when(relationshipRepository.delete(USER)).thenReturn(Mono.empty());
        service = new AccountErasureService(markers, communityRepository, communityService, relationshipRepository,
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Community membership(String communityId, CommunityRole role) {
        return new Community(communityId, communityId, null, List.of(), CommunityVisibility.PUBLIC, NOW, 3, role,
                List.of(), 0);
    }

    @Test
    void erase_shouldMarkFirstAndSetErasedAtLast_whenTheUserExists() {
        Community club = membership("c1", CommunityRole.ADMIN);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(club));
        when(communityService.leaveOnErasure(USER, club)).thenReturn(Mono.just(CommunityLeave.handedOverTo("bob")));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

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

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

        verify(transactions).inTransaction(any());
        verifyNoInteractions(markers, communityRepository, communityService, relationshipRepository);
    }

    @Test
    void erase_shouldOpenExactlyOneTransaction_whenCalled() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

        assertThat(transactionsOpened).hasValue(1);
    }

    @Test
    void erase_shouldUseTheEventsDeletedAt_whenPresent() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

        verify(markers).markDeleted(USER, DELETED_AT);
    }

    @Test
    @DisplayName("a record from an older producer has no deletedAt: the clock stands in for it")
    void erase_shouldFallBackToTheClock_whenDeletedAtIsNull() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, null))).verifyComplete();

        verify(markers).markDeleted(USER, NOW);
    }

    @Test
    void erase_shouldHandOverEveryCommunityInOrder_whenTheUserBelongsToSeveral() {
        Community first = membership("c1", CommunityRole.ADMIN);
        Community second = membership("c2", CommunityRole.MEMBER);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(first, second));
        when(communityService.leaveOnErasure(USER, first)).thenReturn(Mono.just(CommunityLeave.deleted()));
        when(communityService.leaveOnErasure(USER, second)).thenReturn(Mono.just(CommunityLeave.left()));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

        InOrder order = inOrder(communityService, relationshipRepository);
        order.verify(communityService).leaveOnErasure(USER, first);
        order.verify(communityService).leaveOnErasure(USER, second);
        order.verify(relationshipRepository).delete(USER);
    }

    @Test
    @DisplayName("a replay finds no node any more, and still sweeps invitations and empty communities")
    void erase_shouldStillSweepInvitationsAndEmptyCommunities_whenTheNodeIsAlreadyGone() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyComplete();

        verify(communityService, never()).leaveOnErasure(anyString(), any());
        verify(communityRepository).deleteInvitationsSentBy(USER);
        verify(communityRepository).deleteEmptyCommunitiesCreatedBy(USER);
        verify(markers).markErased(USER, NOW);
    }

    @Test
    void erase_shouldNotSetErasedAt_whenDeletingTheNodeFails() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(relationshipRepository.delete(USER)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyErrorSatisfies(error ->
                assertThat(error).isSameAs(failure));

        verify(communityRepository, never()).deleteEmptyCommunitiesCreatedBy(anyString());
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldPropagateTheError_whenTheMarkerCannotBeWritten() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(markers.markDeleted(USER, DELETED_AT)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyErrorSatisfies(error ->
                assertThat(error).isSameAs(failure));

        verifyNoInteractions(communityRepository, communityService, relationshipRepository);
    }

    @Test
    void erase_shouldPropagateTheError_whenACommunityCannotBeHandedOver() {
        Community club = membership("c1", CommunityRole.ADMIN);
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(communityRepository.findJoined(USER)).thenReturn(Flux.just(club));
        when(communityService.leaveOnErasure(USER, club)).thenReturn(Mono.error(failure));

        StepVerifier.create(service.erase(new UserDeletedEventDTO(USER, DELETED_AT))).verifyErrorSatisfies(error ->
                assertThat(error).isSameAs(failure));

        verify(relationshipRepository, never()).delete(anyString());
    }

    @Test
    void erase_shouldRefuseWithoutTouchingThePorts_whenTheUserIdIsBlank() {
        StepVerifier.create(service.erase(new UserDeletedEventDTO(" ", DELETED_AT)))
                .verifyError(IllegalArgumentException.class);
        StepVerifier.create(service.erase(new UserDeletedEventDTO(null, DELETED_AT)))
                .verifyError(IllegalArgumentException.class);
        StepVerifier.create(service.erase(null)).verifyError(IllegalArgumentException.class);

        verifyNoInteractions(transactions, markers, communityRepository, communityService, relationshipRepository);
    }
}
