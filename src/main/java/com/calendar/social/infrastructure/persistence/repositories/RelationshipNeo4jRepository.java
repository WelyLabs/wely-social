package com.calendar.social.infrastructure.persistence.repositories;

import com.calendar.social.infrastructure.persistence.models.entities.RelationshipEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import reactor.core.publisher.Mono;

public interface RelationshipNeo4jRepository extends ReactiveNeo4jRepository<RelationshipEntity, String> {

    @Query("MATCH (me:User {userId: $myId})-[r:RELATIONSHIP]-(other:User {userId: $otherId}) " +
            "WHERE r.status = 'ACCEPTED' " +
            "WITH r, r AS deletedRel " +
            "DELETE r " +
            "RETURN deletedRel")
    Mono<RelationshipEntity> deleteFriendship(String myId, String otherId);
}
