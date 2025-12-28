package com.calendar.social.infrastucture.repositories;

import com.calendar.social.infrastucture.models.entities.RelationshipEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import reactor.core.publisher.Mono;

public interface RelationshipRepository extends ReactiveNeo4jRepository<RelationshipEntity, Long> {

    @Query("MATCH (me:User {userId: $myId})-[r:RELATIONSHIP]-(other:User {userId: $otherId}) " +
            "WHERE r.status = 'ACCEPTED' " +
            "WITH r, r AS deletedRel " +
            "DELETE r " +
            "RETURN deletedRel")
    Mono<RelationshipEntity> deleteFriendship(Long myId, Long otherId);
}
