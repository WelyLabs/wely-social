package com.calendar.social.infrastructure.persistence.schema;

import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Backfills FRIENDSHIP_CHANGED and FRIENDSHIP_STATE once: one SNAPSHOT record per accepted pair, so a
 * consumer that starts from the beginning learns the friendships made before the outbox existed. The
 * {@code (:Migration {name: <app.friendships.snapshot-name>})} marker makes every later start a
 * no-op; the topic keeps records for days only, so a new name re-runs it for a rebuilt projection.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.friendship-outbox.snapshot", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FriendshipSnapshotMigration implements SmartLifecycle {

    static final long ALREADY_DONE = -1;

    private final Neo4jFriendshipOutboxAdapter outbox;
    private final Duration firstBackoff;
    private final Duration maxBackoff;

    private volatile Disposable task;

    public FriendshipSnapshotMigration(
            Neo4jFriendshipOutboxAdapter outbox,
            @Value("${app.neo4j.schema-init.first-backoff:1s}") Duration firstBackoff,
            @Value("${app.neo4j.schema-init.max-backoff:5m}") Duration maxBackoff) {
        this.outbox = outbox;
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public void start() {
        // A self-driven startup task, like Neo4jSchemaInitializer: nothing else subscribes to it.
        task = outbox.enqueueSnapshotOnce()
                // Empty when the marker exists: the backfill ran on an earlier start.
                .defaultIfEmpty(ALREADY_DONE)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, firstBackoff)
                        .maxBackoff(maxBackoff)
                        .doBeforeRetry(signal -> log.warn("Friendship snapshot not enqueued yet, retrying: {}",
                                signal.failure().getMessage())))
                .subscribe(
                        count -> {
                            if (count == ALREADY_DONE) {
                                log.debug("Friendship snapshot already done");
                            } else {
                                log.info("Friendship snapshot enqueued: {} accepted pair(s)", count);
                            }
                        },
                        error -> log.error("Friendship snapshot could not be enqueued", error));
    }

    @Override
    public void stop() {
        Disposable running = task;
        task = null;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return task != null;
    }
}
