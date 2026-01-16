package com.calendar.social.infrastucture.persistence.repositories;

import com.calendar.social.infrastucture.persistence.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastucture.persistence.models.entities.UserNodeEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserNodeRepository extends ReactiveNeo4jRepository<UserNodeEntity, Long> {

    @Query("MATCH (me:User {userId: $userId}) " +
            "MATCH (target:User {userName: $targetName, hashtag: $targetHashtag}) " +
            "WHERE me.userId <> target.userId " +
            "AND NOT (me)-[:RELATIONSHIP]-(target) " +
            "MERGE (me)-[r:RELATIONSHIP {status: 'PENDING', createdAt: datetime()}]->(target) " +
            "RETURN DISTINCT target")
    Mono<UserNodeEntity> sendFriendRequest(String userId, String targetName, Integer targetHashtag);

    @Query("MATCH (sender:User {userId: $senderId})-[r:RELATIONSHIP {status: 'PENDING'}]->(me:User {userId: $myId}) " +
            "SET r.status = 'ACCEPTED', r.acceptedAt = datetime() " +
            "RETURN DISTINCT sender")
    Mono<UserNodeEntity> acceptFriendRequest(String myId, String senderId);

    @Query("MATCH (sender:User {userId: $senderId})-[r:RELATIONSHIP {status: 'PENDING'}]->(me:User {userId: $myId}) " +
            "SET r.status = 'REJECTED', r.rejectedAt = datetime() " +
            "RETURN DISTINCT sender")
    Mono<UserNodeEntity> rejectFriendRequest(String myId, String senderId);

    @Query("MATCH (me:User {userId: $userId})\n" +
            "MATCH (other:User) \n" +
            "WHERE other.userId <> me.userId\n" +
            "OPTIONAL MATCH (me)-[r:RELATIONSHIP]-(other)\n" +
            "RETURN DISTINCT\n" +
            "    other.userId AS userId,\n" +
            "    other.userName AS userName,\n" +
            "    other.profilePicUrl AS profilePicUrl,\n" +
            "    CASE \n" +
            "        WHEN r IS NULL THEN 'NONE'\n" +
            "        WHEN r.status = 'ACCEPTED' THEN 'FRIENDS'\n" +
            "        WHEN r.status = 'PENDING' AND startNode(r) = me THEN 'SENT_BY_ME'\n" +
            "        WHEN r.status = 'PENDING' AND startNode(r) = other THEN 'SENT_BY_THEM'\n" +
            "        ELSE 'NONE'\n" +
            "    END AS relationStatus\n" +
            "ORDER BY userName ASC")
    Flux<UserSocialDBDTO> findAllWithSocialStatus(String userId);

    @Query("MATCH (me:User {userId: $userId})-[:RELATIONSHIP {status: 'ACCEPTED'}]-(targetUser:User) " +
            "RETURN DISTINCT targetUser")
    Flux<UserNodeEntity> findAllFriends(String userId);

    @Query("MATCH (me:User {userId: $userId})-[:RELATIONSHIP {status: 'PENDING'}]->(targetUser:User) " +
            "RETURN DISTINCT targetUser")
    Flux<UserNodeEntity> findOutgoingRequests(String userId);

    @Query("MATCH (me:User {userId: $userId})<-[:RELATIONSHIP {status: 'PENDING'}]-(targetUser:User) " +
            "RETURN DISTINCT targetUser")
    Flux<UserNodeEntity> findIncomingRequests(String userId);

    Mono<Boolean> existsByUserNameAndHashtag(String userName, Integer hashtag);
}
