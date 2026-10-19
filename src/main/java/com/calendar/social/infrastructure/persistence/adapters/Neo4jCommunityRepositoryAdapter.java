package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Communities, memberships and invitations in the social graph, through Cypher on {@link ReactiveNeo4jClient}. */
@Component
public class Neo4jCommunityRepositoryAdapter implements CommunityRepository {

    /** How many of the viewer's friends a community names; the count says how many there are in all. */
    static final int FRIEND_MEMBERS_SHOWN = 5;

    /** Continues a query that has bound {@code c} to one community, and returns it as {@code $viewerId} sees it. */
    private static final String VIEW = """
            WITH DISTINCT c
            OPTIONAL MATCH (:User {userId: $viewerId})-[my:MEMBER_OF]->(c)
            WITH c, my.role AS role
            OPTIONAL MATCH (:User {userId: $viewerId})-[:RELATIONSHIP {status: 'ACCEPTED'}]-(f:User)-[:MEMBER_OF]->(c)
            WITH c, role, collect(DISTINCT f) AS friends
            RETURN c.communityId AS communityId, c.name AS name, c.description AS description,
                   c.interests AS interests, c.visibility AS visibility, c.createdAt AS createdAt,
                   COUNT { (:User)-[:MEMBER_OF]->(c) } AS memberCount, role,
                   [x IN friends[0..%d] | x {.userId, .userName, .hashtag, .profilePicUrl}] AS friendMembers,
                   size(friends) AS friendMemberCount, c.picture AS pictureKey
            """.formatted(FRIEND_MEMBERS_SHOWN);

    private static final String INVITATION = """
            OPTIONAL MATCH (inviter:User {userId: i.inviterId})
            RETURN i.token AS token, c.communityId AS communityId, c.name AS communityName,
                   c.visibility AS visibility, i.inviterId AS inviterId, inviter.userName AS inviterName,
                   invitee.userId AS inviteeId, invitee.userName AS inviteeName,
                   i.createdAt AS createdAt, i.expiresAt AS expiresAt
            """;

    private final ReactiveNeo4jClient client;

    public Neo4jCommunityRepositoryAdapter(ReactiveNeo4jClient client) {
        this.client = client;
    }

    /** The creator's node must exist: a community without its admin would be nobody's to manage. */
    @Override
    public Mono<Community> create(String creatorId, CommunityDraft draft, Instant now) {
        Map<String, Object> params = draftParams(draft);
        params.put("communityId", UUID.randomUUID().toString());
        params.put("creatorId", creatorId);
        params.put("now", utc(now));
        return client.query("""
                        MATCH (u:User {userId: $creatorId})
                        CREATE (c:Community {communityId: $communityId, name: $name, description: $description,
                                             interests: $interests, visibility: $visibility,
                                             createdAt: $now, createdBy: $creatorId})
                        CREATE (u)-[:MEMBER_OF {role: 'ADMIN', joinedAt: $now}]->(c)
                        RETURN c.communityId AS communityId
                        """)
                .bindAll(params)
                .fetchAs(String.class).mappedBy((types, row) -> row.get("communityId").asString())
                .one()
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)))
                .flatMap(communityId -> findById(communityId, creatorId));
    }

    @Override
    public Mono<Community> update(String communityId, String viewerId, CommunityDraft draft) {
        Map<String, Object> params = draftParams(draft);
        params.put("communityId", communityId);
        params.put("viewerId", viewerId);
        return client.query("""
                        MATCH (c:Community {communityId: $communityId})
                        SET c.name = $name, c.description = $description,
                            c.interests = $interests, c.visibility = $visibility
                        """ + VIEW)
                .bindAll(params)
                .fetchAs(Community.class).mappedBy((types, row) -> toCommunity(row))
                .one();
    }

    /** A null key removes the picture. */
    @Override
    public Mono<Community> setPicture(String communityId, String viewerId, String pictureKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("communityId", communityId);
        params.put("viewerId", viewerId);
        params.put("pictureKey", pictureKey);
        return client.query("""
                        MATCH (c:Community {communityId: $communityId})
                        SET c.picture = $pictureKey
                        """ + VIEW)
                .bindAll(params)
                .fetchAs(Community.class).mappedBy((types, row) -> toCommunity(row))
                .one();
    }

    @Override
    public Mono<Void> delete(String communityId) {
        return client.query("MATCH (c:Community {communityId: $communityId}) DETACH DELETE c")
                .bind(communityId).to("communityId")
                .run()
                .then();
    }

    @Override
    public Mono<Community> findById(String communityId, String viewerId) {
        return client.query("MATCH (c:Community {communityId: $communityId})\n" + VIEW)
                .bindAll(Map.of("communityId", communityId, "viewerId", viewerId))
                .fetchAs(Community.class).mappedBy((types, row) -> toCommunity(row))
                .one();
    }

    @Override
    public Flux<Community> findPublic(String viewerId, String query, String interest, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("viewerId", viewerId);
        params.put("query", query);
        params.put("interest", interest);
        params.put("limit", limit);
        return client.query("""
                        MATCH (c:Community {visibility: 'PUBLIC'})
                        WHERE ($query IS NULL
                               OR toLower(c.name) CONTAINS toLower($query)
                               OR toLower(coalesce(c.description, '')) CONTAINS toLower($query))
                          AND ($interest IS NULL OR $interest IN c.interests)
                        """ + VIEW + "ORDER BY memberCount DESC, toLower(name) ASC LIMIT $limit")
                .bindAll(params)
                .fetchAs(Community.class).mappedBy((types, row) -> toCommunity(row))
                .all();
    }

    @Override
    public Flux<Community> findJoined(String userId) {
        return client.query("MATCH (:User {userId: $viewerId})-[:MEMBER_OF]->(c:Community)\n"
                        + VIEW + "ORDER BY toLower(name) ASC")
                .bind(userId).to("viewerId")
                .fetchAs(Community.class).mappedBy((types, row) -> toCommunity(row))
                .all();
    }

    @Override
    public Mono<CommunityRole> roleOf(String communityId, String userId) {
        return client.query("""
                        MATCH (:User {userId: $userId})-[m:MEMBER_OF]->(:Community {communityId: $communityId})
                        RETURN m.role AS role
                        """)
                .bindAll(Map.of("communityId", communityId, "userId", userId))
                .fetchAs(CommunityRole.class).mappedBy((types, row) -> CommunityRole.valueOf(row.get("role").asString()))
                .one();
    }

    /** MERGE, so a second join changes nothing, and ON CREATE, so it never demotes an admin. */
    @Override
    public Mono<Void> addMember(String communityId, String userId, CommunityRole role, Instant now) {
        return client.query("""
                        MATCH (u:User {userId: $userId}), (c:Community {communityId: $communityId})
                        MERGE (u)-[m:MEMBER_OF]->(c)
                        ON CREATE SET m.role = $role, m.joinedAt = $now
                        """)
                .bindAll(Map.of("communityId", communityId, "userId", userId, "role", role.name(), "now", utc(now)))
                .run()
                .then();
    }

    @Override
    public Mono<Boolean> removeMember(String communityId, String userId) {
        return client.query("""
                        OPTIONAL MATCH (:User {userId: $userId})-[m:MEMBER_OF]->(:Community {communityId: $communityId})
                        WITH m, m IS NOT NULL AS existed
                        DELETE m
                        RETURN existed
                        """)
                .bindAll(Map.of("communityId", communityId, "userId", userId))
                .fetchAs(Boolean.class).mappedBy((types, row) -> row.get("existed").asBoolean())
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> setRole(String communityId, String userId, CommunityRole role) {
        return client.query("""
                        OPTIONAL MATCH (:User {userId: $userId})-[m:MEMBER_OF]->(:Community {communityId: $communityId})
                        SET m.role = $role
                        RETURN m IS NOT NULL AS updated
                        """)
                .bindAll(Map.of("communityId", communityId, "userId", userId, "role", role.name()))
                .fetchAs(Boolean.class).mappedBy((types, row) -> row.get("updated").asBoolean())
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<CommunityMember> findMembers(String communityId) {
        return client.query("""
                        MATCH (u:User)-[m:MEMBER_OF]->(:Community {communityId: $communityId})
                        RETURN u.userId AS userId, u.userName AS userName, u.hashtag AS hashtag,
                               u.profilePicUrl AS profilePicUrl, m.role AS role, m.joinedAt AS joinedAt
                        ORDER BY CASE m.role WHEN 'ADMIN' THEN 0 ELSE 1 END, m.joinedAt ASC
                        """)
                .bind(communityId).to("communityId")
                .fetchAs(CommunityMember.class).mappedBy((types, row) -> new CommunityMember(
                        row.get("userId").asString(),
                        nullableString(row.get("userName")),
                        nullableInt(row.get("hashtag")),
                        nullableString(row.get("profilePicUrl")),
                        CommunityRole.valueOf(row.get("role").asString()),
                        instant(row.get("joinedAt"))))
                .all();
    }

    @Override
    public Mono<Long> countAdmins(String communityId, Set<String> excludedUserIds) {
        return client.query("""
                        MATCH (c:Community {communityId: $communityId})
                        RETURN COUNT {
                            MATCH (u:User)-[:MEMBER_OF {role: 'ADMIN'}]->(c)
                            WHERE NOT u.userId IN $excludedUserIds
                              AND NOT EXISTS { MATCH (:DeletedUser {userId: u.userId}) }
                        } AS admins
                        """)
                .bindAll(Map.of("communityId", communityId, "excludedUserIds", List.copyOf(excludedUserIds)))
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("admins").asLong())
                .one()
                .defaultIfEmpty(0L);
    }

    /** The userId tie-break makes two erasures of the same user pick the same successor. */
    @Override
    public Mono<String> findLongestStandingMember(String communityId, Set<String> excludedUserIds) {
        return client.query("""
                        MATCH (u:User)-[m:MEMBER_OF]->(:Community {communityId: $communityId})
                        WHERE NOT u.userId IN $excludedUserIds
                          AND NOT EXISTS { MATCH (:DeletedUser {userId: u.userId}) }
                        RETURN u.userId AS userId
                        ORDER BY m.joinedAt ASC, u.userId ASC
                        LIMIT 1
                        """)
                .bindAll(Map.of("communityId", communityId, "excludedUserIds", List.copyOf(excludedUserIds)))
                .fetchAs(String.class).mappedBy((types, row) -> row.get("userId").asString())
                .one();
    }

    @Override
    public Mono<CommunityInvitation> saveInvitation(String communityId, String inviterId, String inviteeId,
                                                    String token, Instant createdAt, Instant expiresAt) {
        return client.query("""
                        MATCH (c:Community {communityId: $communityId}), (invitee:User {userId: $inviteeId})
                        OPTIONAL MATCH (c)-[old:INVITED]->(invitee)
                        DELETE old
                        WITH DISTINCT c, invitee
                        CREATE (c)-[:INVITED {token: $token, inviterId: $inviterId,
                                              createdAt: $createdAt, expiresAt: $expiresAt}]->(invitee)
                        """)
                .bindAll(Map.of("communityId", communityId, "inviterId", inviterId, "inviteeId", inviteeId,
                        "token", token, "createdAt", utc(createdAt), "expiresAt", utc(expiresAt)))
                .run()
                .then(findInvitation(token));
    }

    @Override
    public Mono<CommunityInvitation> findInvitation(String token) {
        return client.query("MATCH (c:Community)-[i:INVITED {token: $token}]->(invitee:User)\n" + INVITATION)
                .bind(token).to("token")
                .fetchAs(CommunityInvitation.class).mappedBy((types, row) -> toInvitation(row))
                .one();
    }

    @Override
    public Flux<CommunityInvitation> findInvitations(String communityId) {
        return client.query("MATCH (c:Community {communityId: $communityId})-[i:INVITED]->(invitee:User)\n"
                        + INVITATION + "ORDER BY createdAt DESC")
                .bind(communityId).to("communityId")
                .fetchAs(CommunityInvitation.class).mappedBy((types, row) -> toInvitation(row))
                .all();
    }

    @Override
    public Mono<Boolean> deleteInvitation(String communityId, String token) {
        return client.query("""
                        OPTIONAL MATCH (:Community {communityId: $communityId})-[i:INVITED {token: $token}]->(:User)
                        WITH i, i IS NOT NULL AS existed
                        DELETE i
                        RETURN existed
                        """)
                .bindAll(Map.of("communityId", communityId, "token", token))
                .fetchAs(Boolean.class).mappedBy((types, row) -> row.get("existed").asBoolean())
                .one()
                .defaultIfEmpty(false);
    }

    /** inviterId is a property, not an edge: deleting the inviter's node does not reach these. */
    @Override
    public Mono<Long> deleteInvitationsSentBy(String inviterId) {
        return client.query("""
                        MATCH (:Community)-[i:INVITED {inviterId: $inviterId}]->(:User)
                        DELETE i
                        RETURN count(*) AS deleted
                        """)
                .bind(inviterId).to("inviterId")
                .fetchAs(Long.class).mappedBy((types, row) -> row.get("deleted").asLong())
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Flux<String> deleteEmptyCommunitiesCreatedBy(String creatorId) {
        return client.query("""
                        MATCH (c:Community {createdBy: $creatorId})
                        WHERE NOT EXISTS { MATCH (:User)-[:MEMBER_OF]->(c) }
                        WITH c, c.communityId AS communityId
                        DETACH DELETE c
                        RETURN communityId
                        """)
                .bind(creatorId).to("creatorId")
                .fetchAs(String.class).mappedBy((types, row) -> row.get("communityId").asString())
                .all();
    }

    private static Map<String, Object> draftParams(CommunityDraft draft) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", draft.name());
        params.put("description", draft.description());
        params.put("interests", draft.interests());
        params.put("visibility", draft.visibility().name());
        return params;
    }

    static Community toCommunity(Record row) {
        String role = nullableString(row.get("role"));
        return new Community(
                row.get("communityId").asString(),
                row.get("name").asString(),
                nullableString(row.get("description")),
                row.get("interests").isNull() ? List.of() : row.get("interests").asList(Value::asString),
                CommunityVisibility.valueOf(row.get("visibility").asString()),
                instant(row.get("createdAt")),
                row.get("memberCount").asLong(),
                role == null ? null : CommunityRole.valueOf(role),
                row.get("friendMembers").asList(friend -> new UserNodeDTO(
                        friend.get("userId").asString(),
                        nullableString(friend.get("userName")),
                        nullableInt(friend.get("hashtag")),
                        nullableString(friend.get("profilePicUrl")))),
                row.get("friendMemberCount").asLong(),
                nullableString(row.get("pictureKey")));
    }

    static CommunityInvitation toInvitation(Record row) {
        return new CommunityInvitation(
                row.get("token").asString(),
                row.get("communityId").asString(),
                row.get("communityName").asString(),
                CommunityVisibility.valueOf(row.get("visibility").asString()),
                row.get("inviterId").asString(),
                nullableString(row.get("inviterName")),
                row.get("inviteeId").asString(),
                nullableString(row.get("inviteeName")),
                instant(row.get("createdAt")),
                instant(row.get("expiresAt")));
    }

    private static ZonedDateTime utc(Instant instant) {
        return instant.atZone(ZoneOffset.UTC);
    }

    private static Instant instant(Value value) {
        return value.isNull() ? null : value.asZonedDateTime().toInstant();
    }

    private static String nullableString(Value value) {
        return value.isNull() ? null : value.asString();
    }

    private static Integer nullableInt(Value value) {
        return value.isNull() ? null : value.asInt();
    }
}
