package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import com.calendar.social.infrastructure.persistence.repositories.FriendshipOutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The FRIENDSHIP_CHANGED outbox in Neo4j: appended to by the domain, drained by
 * {@code FriendshipOutboxRelay}. Failures are translated by {@code PersistenceErrorAspect}.
 */
@Component
public class Neo4jFriendshipOutboxAdapter implements FriendshipEventOutbox {

    /** The record type the backfill writes, which wely-notifications ignores. */
    public static final String SNAPSHOT_TYPE = "SNAPSHOT";
    static final int MAX_STORED_ERROR_LENGTH = 1000;

    private final FriendshipOutboxEventRepository repository;
    private final String snapshotName;

    /** A new {@code snapshotName} runs the backfill once more, for a consumer that cannot replay the topic. */
    public Neo4jFriendshipOutboxAdapter(
            FriendshipOutboxEventRepository repository,
            @Value("${app.friendships.snapshot-name:friendship-snapshot-v1}") String snapshotName) {
        this.repository = repository;
        this.snapshotName = snapshotName;
    }

    /** Joins the caller's transaction, the one that made the change. */
    @Override
    public Mono<Void> append(FriendshipEvent event) {
        String previous = event.previousCloseness() == null ? null : event.previousCloseness().name();
        return repository.append(event.type().name(), event.fromUserId(), event.toUserId(), previous)
                // CREATE always returns the record: empty would mean it was not written.
                .switchIfEmpty(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)))
                .then();
    }

    /** How many records the backfill enqueued; empty when the one named {@code snapshotName} already ran. */
    public Mono<Long> enqueueSnapshotOnce() {
        return repository.enqueueSnapshotOnce(snapshotName, SNAPSHOT_TYPE);
    }

    public Flux<FriendshipOutboxEventEntity> findPending(int limit) {
        return repository.findPending(limit);
    }

    public Mono<Long> markPublished(long version) {
        return repository.markPublished(version);
    }

    public Mono<Long> recordFailure(long version, String error) {
        return repository.recordFailure(version, truncate(error));
    }

    public Mono<Long> purgePublishedBefore(Instant cutoff, int limit) {
        return repository.deletePublishedBefore(cutoff.atZone(ZoneOffset.UTC), limit);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_STORED_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_STORED_ERROR_LENGTH);
    }
}
