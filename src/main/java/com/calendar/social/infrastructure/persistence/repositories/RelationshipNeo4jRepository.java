package com.calendar.social.infrastructure.persistence.repositories;

import com.calendar.social.infrastructure.persistence.models.dtos.RelationshipDBDTO;
import com.calendar.social.infrastructure.persistence.models.entities.RelationshipEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import reactor.core.publisher.Mono;

public interface RelationshipNeo4jRepository extends ReactiveNeo4jRepository<RelationshipEntity, String> {

    /**
     * Deletes an accepted friendship and returns what it was.
     *
     * <p>The properties are captured in the {@code WITH} before the {@code DELETE}: Cypher
     * cannot read a relationship once it has been deleted, and returning the relationship
     * itself is not mappable — see {@link RelationshipDBDTO}.
     *
     * <p>The match is undirected, because a friendship is symmetric whichever side sent the
     * original request.
     */
    @Query("MATCH (me:User {userId: $myId})-[r:RELATIONSHIP]-(other:User {userId: $otherId}) "
            + "WHERE r.status = 'ACCEPTED' "
            + "WITH r, "
            + "     r.status AS status, "
            + "     toString(r.createdAt) AS createdAt, "
            + "     toString(r.acceptedAt) AS acceptedAt, "
            + "     toString(r.rejectedAt) AS rejectedAt "
            + "DELETE r "
            + "RETURN status, createdAt, acceptedAt, rejectedAt")
    Mono<RelationshipDBDTO> deleteFriendship(String myId, String otherId);
}
