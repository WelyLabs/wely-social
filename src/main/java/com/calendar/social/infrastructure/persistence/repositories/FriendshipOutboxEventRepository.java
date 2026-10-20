package com.calendar.social.infrastructure.persistence.repositories;

import com.calendar.social.infrastructure.persistence.models.entities.FriendshipOutboxEventEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

/**
 * The FRIENDSHIP_CHANGED outbox: {@code (:FriendshipOutboxEvent)} records and the
 * {@code (:FriendshipClock)} that numbers them.
 *
 * <p>Each record's version is {@code max(clock + 1, now in ms)}, written to the clock in the same
 * statement. Writing the clock takes its lock until commit, so versions are handed out in commit
 * order: the relay can never read version n+1 before n is committed. The clock node outlives any
 * relationship, so a pair deleted and re-created still gets higher versions, and the millisecond
 * floor keeps versions rising even if the graph is wiped.
 */
@Repository
public interface FriendshipOutboxEventRepository extends ReactiveNeo4jRepository<FriendshipOutboxEventEntity, Long> {

    String CLOCK_TICK = "MERGE (clock:FriendshipClock {name: 'friendship-events'}) "
            + "SET clock.value = CASE WHEN coalesce(clock.value, 0) >= timestamp() "
            + "                       THEN clock.value + 1 ELSE timestamp() END ";

    /**
     * Records a change with the pair's state as this transaction sees it: closeness and since when
     * an accepted relationship joins the two, null otherwise. Runs after the change, in its transaction.
     */
    @Query(CLOCK_TICK
            + "WITH clock "
            + "OPTIONAL MATCH (from:User {userId: $fromUserId}) "
            + "OPTIONAL MATCH (:User {userId: $fromUserId})-[r:RELATIONSHIP {status: 'ACCEPTED'}]-(:User {userId: $toUserId}) "
            + "WITH clock, from, head(collect(r)) AS r "
            + "CREATE (e:FriendshipOutboxEvent:Unpublished { "
            + "    version: clock.value, type: $type, "
            + "    fromUserId: $fromUserId, fromUserName: from.userName, toUserId: $toUserId, "
            + "    pairKey: CASE WHEN $fromUserId < $toUserId THEN $fromUserId + ':' + $toUserId "
            + "                  ELSE $toUserId + ':' + $fromUserId END, "
            + "    closeness: CASE WHEN r IS NULL THEN null ELSE coalesce(r.closeness, 'FRIEND') END, "
            + "    previousCloseness: $previousCloseness, "
            + "    since: CASE WHEN r IS NULL THEN null ELSE coalesce(r.acceptedAt, r.createdAt) END, "
            + "    occurredAt: datetime(), attempts: 0 }) "
            + "RETURN e")
    Mono<FriendshipOutboxEventEntity> append(String type, String fromUserId, String toUserId,
                                             String previousCloseness);

    /**
     * Once, when no {@code (:Migration {name: $migration})} exists: one record of {@code $type} per
     * accepted pair, oriented like ACCEPTED (from the recipient of the original request to its sender),
     * with no previousCloseness, then the marker. One statement, so it commits whole. The clock is written before the pairs are
     * read: a change committing meanwhile waits for it, and gets a higher version than the snapshot.
     * Empty when the migration already ran.
     */
    @Query("OPTIONAL MATCH (done:Migration {name: $migration}) "
            + "WITH done WHERE done IS NULL "
            + CLOCK_TICK
            + "WITH clock, clock.value AS base "
            + "OPTIONAL MATCH (requester:User)-[r:RELATIONSHIP {status: 'ACCEPTED'}]->(acceptor:User) "
            + "WITH clock, base, collect(CASE WHEN r IS NULL THEN null ELSE { "
            + "    from: acceptor.userId, fromName: acceptor.userName, to: requester.userId, "
            + "    closeness: coalesce(r.closeness, 'FRIEND'), since: coalesce(r.acceptedAt, r.createdAt) } END) AS pairs "
            + "SET clock.value = base + CASE WHEN size(pairs) > 0 THEN size(pairs) - 1 ELSE 0 END "
            + "FOREACH (i IN range(0, size(pairs) - 1) | "
            + "    CREATE (:FriendshipOutboxEvent:Unpublished { "
            + "        version: base + i, type: $type, "
            + "        fromUserId: pairs[i].from, fromUserName: pairs[i].fromName, toUserId: pairs[i].to, "
            + "        pairKey: CASE WHEN pairs[i].from < pairs[i].to THEN pairs[i].from + ':' + pairs[i].to "
            + "                      ELSE pairs[i].to + ':' + pairs[i].from END, "
            + "        closeness: pairs[i].closeness, since: pairs[i].since, "
            + "        occurredAt: datetime(), attempts: 0 })) "
            + "MERGE (m:Migration {name: $migration}) ON CREATE SET m.ranAt = datetime() "
            + "RETURN size(pairs)")
    Mono<Long> enqueueSnapshotOnce(String migration, String type);

    /** How many records wait to be sent; parked ones are not pending. */
    @Query("MATCH (e:FriendshipOutboxEvent:Unpublished) RETURN count(e)")
    Mono<Long> countPending();

    /** The oldest pending records, in version order. */
    @Query("MATCH (e:FriendshipOutboxEvent:Unpublished) RETURN e ORDER BY e.version LIMIT $limit")
    Flux<FriendshipOutboxEventEntity> findPending(int limit);

    @Query("MATCH (e:FriendshipOutboxEvent:Unpublished {version: $version}) "
            + "REMOVE e:Unpublished SET e.publishedAt = datetime() "
            + "RETURN count(e)")
    Mono<Long> markPublished(Long version);

    @Query("MATCH (e:FriendshipOutboxEvent:Unpublished {version: $version}) "
            + "SET e.attempts = coalesce(e.attempts, 0) + 1, e.lastError = $error "
            + "RETURN count(e)")
    Mono<Long> recordFailure(Long version, String error);

    /** Takes a record out of the pending ones for good, counting the attempt that made the relay give up. */
    @Query("MATCH (e:FriendshipOutboxEvent:Unpublished {version: $version}) "
            + "REMOVE e:Unpublished SET e:Parked, e.parkedAt = datetime(), "
            + "    e.attempts = coalesce(e.attempts, 0) + 1, e.lastError = $error "
            + "RETURN count(e)")
    Mono<Long> park(Long version, String error);

    /** Deletes at most {@code limit} records published or parked before the cutoff; never a pending one. */
    @Query("MATCH (e:FriendshipOutboxEvent) WHERE e.publishedAt < $cutoff OR e.parkedAt < $cutoff "
            + "WITH e LIMIT $limit "
            + "DELETE e "
            + "RETURN count(*)")
    Mono<Long> deleteSettledBefore(ZonedDateTime cutoff, int limit);
}
