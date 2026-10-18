package com.calendar.social.domain.services;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.domain.models.UserDeletedEventDTO;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Erases a deleted account from the social graph, leaving only a pseudonymous marker. */
public class AccountErasureService {

    // JDK logger: the domain takes no logging dependency.
    private static final System.Logger LOG = System.getLogger(AccountErasureService.class.getName());

    private final DeletedUserMarkers markers;
    private final CommunityRepository communityRepository;
    private final CommunityService communityService;
    private final RelationshipRepository relationshipRepository;
    private final FriendshipEventOutbox outbox;
    private final TransactionRunner transactions;
    private final Clock clock;

    public AccountErasureService(DeletedUserMarkers markers, CommunityRepository communityRepository,
                                 CommunityService communityService, RelationshipRepository relationshipRepository,
                                 FriendshipEventOutbox outbox, TransactionRunner transactions, Clock clock) {
        this.markers = markers;
        this.communityRepository = communityRepository;
        this.communityService = communityService;
        this.relationshipRepository = relationshipRepository;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Marks the user deleted and removes them from the graph in one idempotent transaction, recording
     * a REMOVED for each former friendship. The records come after the delete, so the transaction
     * locks relationships before the outbox clock, in the same order as every other friendship write.
     */
    public Mono<Void> erase(UserDeletedEventDTO event) {
        if (event == null || event.userId() == null || event.userId().isBlank()) {
            return Mono.error(new IllegalArgumentException("An erasure needs a userId"));
        }
        String userId = event.userId();
        // A record from an older producer has no deletedAt: the processing time stands in for it.
        Instant deletedAt = event.deletedAt() != null ? event.deletedAt() : clock.instant();
        return transactions.inTransaction(Mono.defer(() -> markers.markDeleted(userId, deletedAt))
                        // Collected before any write: a reactive transaction runs one query at a time.
                        .then(Mono.defer(() -> relationshipRepository.findFriendSummaries(userId).collectList()))
                        .flatMap(friends -> Mono.defer(() -> communityRepository.findJoined(userId).collectList())
                                .flatMapMany(Flux::fromIterable)
                                .concatMap(membership -> communityService.leaveOnErasure(userId, membership))
                                .then(Mono.defer(() -> communityRepository.deleteInvitationsSentBy(userId)))
                                .then(Mono.defer(() -> relationshipRepository.delete(userId)))
                                .then(Mono.defer(() -> recordRemovals(userId, friends))))
                        .then(Mono.defer(() -> communityRepository.deleteEmptyCommunitiesCreatedBy(userId)))
                        .then(Mono.defer(() -> markers.markErased(userId, clock.instant()))))
                .doOnSuccess(unused -> LOG.log(System.Logger.Level.INFO,
                        "Account erased from the social graph, userId={0}", userId));
    }

    private Mono<Void> recordRemovals(String userId, List<FriendSummary> friends) {
        return Flux.fromIterable(friends)
                .concatMap(friend -> outbox.append(new FriendshipEvent(FriendshipEventType.REMOVED, userId,
                        friend.userId(), friend.closeness())))
                .then();
    }
}
