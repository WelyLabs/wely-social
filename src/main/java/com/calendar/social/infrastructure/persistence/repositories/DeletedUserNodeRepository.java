package com.calendar.social.infrastructure.persistence.repositories;

import com.calendar.social.infrastructure.persistence.models.entities.DeletedUserNodeEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

@Repository
public interface DeletedUserNodeRepository extends ReactiveNeo4jRepository<DeletedUserNodeEntity, String> {

    /** Creates the marker if absent; ON CREATE keeps the first deletedAt on a replay. */
    @Query("MERGE (d:DeletedUser {userId: $userId}) "
            + "ON CREATE SET d.deletedAt = $deletedAt "
            + "RETURN d")
    Mono<DeletedUserNodeEntity> markDeleted(String userId, ZonedDateTime deletedAt);

    /** Sets erasedAt once; empty when there is no marker. */
    @Query("MATCH (d:DeletedUser {userId: $userId}) "
            + "SET d.erasedAt = coalesce(d.erasedAt, $erasedAt) "
            + "RETURN d")
    Mono<DeletedUserNodeEntity> markErased(String userId, ZonedDateTime erasedAt);
}
