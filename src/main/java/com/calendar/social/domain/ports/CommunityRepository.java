package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Communities, their members and their invitations, in the social graph. */
public interface CommunityRepository {

    /** Creates the community with its creator as its first admin, and returns it as the creator sees it. */
    Mono<Community> create(String creatorId, CommunityDraft draft, Instant now);

    /** Replaces the community's name, description, interests and visibility; empty if it does not exist. */
    Mono<Community> update(String communityId, String viewerId, CommunityDraft draft);

    /** Deletes the community with its memberships and invitations. Deleting a missing one is not an error. */
    Mono<Void> delete(String communityId);

    /** The community as the viewer sees it, whether they may see it or not: the caller decides. Empty if unknown. */
    Mono<Community> findById(String communityId, String viewerId);

    /**
     * Public communities, most members first, at most {@code limit}. {@code query} matches the name or the
     * description, ignoring case; {@code interest} one of the interests exactly. Either may be null.
     */
    Flux<Community> findPublic(String viewerId, String query, String interest, int limit);

    /** The communities the user is a member of, public and private, by name. */
    Flux<Community> findJoined(String userId);

    /** The user's role in the community; empty when they are not a member. */
    Mono<CommunityRole> roleOf(String communityId, String userId);

    /** Makes the user a member with this role, keeping the role of one who already is; idempotent. */
    Mono<Void> addMember(String communityId, String userId, CommunityRole role, Instant now);

    /** Whether a membership was removed. */
    Mono<Boolean> removeMember(String communityId, String userId);

    /** Whether the user was a member, and now has this role. */
    Mono<Boolean> setRole(String communityId, String userId, CommunityRole role);

    /** Admins first, then by when they joined. */
    Flux<CommunityMember> findMembers(String communityId);

    Mono<Long> countAdmins(String communityId);

    /** The member who joined the earliest besides {@code excludedUserId}; empty when there is none. */
    Mono<String> findLongestStandingMember(String communityId, String excludedUserId);

    /** Stores the invitation, replacing any other the invitee had to this community. */
    Mono<CommunityInvitation> saveInvitation(String communityId, String inviterId, String inviteeId,
                                             String token, Instant createdAt, Instant expiresAt);

    Mono<CommunityInvitation> findInvitation(String token);

    /** The community's invitations not used nor revoked yet, expired ones included, newest first. */
    Flux<CommunityInvitation> findInvitations(String communityId);

    /** Whether an invitation of this community with this token was deleted. */
    Mono<Boolean> deleteInvitation(String communityId, String token);
}
