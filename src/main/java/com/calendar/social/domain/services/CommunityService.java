package com.calendar.social.domain.services;

import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityPicture;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.MediaStore;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.SocialActivity;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Communities: public ones anyone joins at once, private ones hidden from non-members and joined by invitation. */
public class CommunityService {

    static final int DISCOVERY_LIMIT = 50;
    /** wely-media signs at most this many keys per call. */
    static final int MAX_URLS_PER_CALL = 200;
    /** Folders purged at once when several communities go together. */
    static final int PURGE_CONCURRENCY = 4;

    // JDK logger: the domain takes no logging dependency.
    private static final System.Logger LOG = System.getLogger(CommunityService.class.getName());

    private final CommunityRepository communityRepository;
    private final RelationshipRepository relationshipRepository;
    private final MediaStore mediaStore;
    private final Clock clock;
    private final Supplier<String> tokenGenerator;
    // Demo accounts, reset every night: never counted as remaining admins nor promoted by a hand-over.
    private final Set<String> protectedUserIds;
    private final SocialActivity activity;

    public CommunityService(CommunityRepository communityRepository, RelationshipRepository relationshipRepository,
                            MediaStore mediaStore, Clock clock, Supplier<String> tokenGenerator,
                            Set<String> protectedUserIds, SocialActivity activity) {
        this.communityRepository = communityRepository;
        this.relationshipRepository = relationshipRepository;
        this.mediaStore = mediaStore;
        this.clock = clock;
        this.tokenGenerator = tokenGenerator;
        this.protectedUserIds = Set.copyOf(protectedUserIds);
        this.activity = activity;
    }

    public Mono<Community> create(String userId, CommunityDraft draft) {
        return valid(draft).flatMap(checked -> communityRepository.create(userId, checked, clock.instant()))
                .doOnNext(created -> activity.communityCreated());
    }

    public Mono<Community> update(String userId, String communityId, CommunityDraft draft) {
        return requireAdmin(communityId, userId)
                .then(Mono.defer(() -> valid(draft)))
                .flatMap(checked -> communityRepository.update(communityId, userId, checked))
                .switchIfEmpty(notFound());
    }

    public Mono<Void> delete(String userId, String communityId) {
        return requireAdmin(communityId, userId).then(Mono.defer(() -> deleteWithPicture(communityId)));
    }

    /**
     * Admins only. The new picture is stored before the community points at it, and the old one is
     * discarded after: a failure in between leaves an orphan in the folder, never a broken picture.
     */
    public Mono<Community> setPicture(String userId, String communityId, byte[] content) {
        return admin(communityId, userId).flatMap(community -> {
            String key = CommunityPicture.newKey(communityId);
            return mediaStore.store(key, content)
                    .then(Mono.defer(() -> communityRepository.setPicture(communityId, userId, key)
                            .switchIfEmpty(notFound())
                            .onErrorResume(error -> discard(key).then(Mono.error(error)))))
                    .flatMap(updated -> discard(community.pictureKey()).thenReturn(updated));
        });
    }

    /** Admins only; a community without a picture is returned as it is. */
    public Mono<Community> removePicture(String userId, String communityId) {
        return admin(communityId, userId).flatMap(community -> community.pictureKey() == null
                ? Mono.just(community)
                : communityRepository.setPicture(communityId, userId, null)
                        .switchIfEmpty(notFound())
                        .flatMap(updated -> discard(community.pictureKey()).thenReturn(updated)));
    }

    /**
     * Signed URLs of these communities' pictures, by key, in calls of at most 200 keys. wely-media
     * failing costs the pictures, not the response: the clients fall back to the initials.
     */
    public Mono<Map<String, String>> pictureUrls(Collection<Community> communities) {
        List<String> keys = communities.stream().map(Community::pictureKey).filter(Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Flux.range(0, (keys.size() + MAX_URLS_PER_CALL - 1) / MAX_URLS_PER_CALL)
                .map(chunk -> keys.subList(chunk * MAX_URLS_PER_CALL, Math.min(keys.size(), (chunk + 1) * MAX_URLS_PER_CALL)))
                .concatMap(chunk -> mediaStore.urls(chunk)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Community pictures sent without URL: {0}", error.toString());
                            return Mono.just(Map.of());
                        }))
                .collect(HashMap<String, String>::new, Map::putAll)
                .map(Map::copyOf);
    }

    /**
     * Best effort: empties the folders of communities already deleted, current and orphaned pictures
     * alike. A failure is logged and swallowed: the community is gone either way.
     */
    public Mono<Void> discardPictures(Collection<String> communityIds) {
        return Flux.fromIterable(Set.copyOf(communityIds))
                .flatMap(communityId -> mediaStore.deletePrefix(CommunityPicture.folder(communityId))
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Pictures of deleted community {0} left in the bucket: {1}",
                                    communityId, error.toString());
                            return Mono.empty();
                        }), PURGE_CONCURRENCY)
                .then();
    }

    private Mono<Void> deleteWithPicture(String communityId) {
        return communityRepository.delete(communityId).then(Mono.defer(() -> discardPictures(List.of(communityId))));
    }

    /** Best effort, like {@link #discardPictures}; a null key is skipped. */
    private Mono<Void> discard(String key) {
        return key == null ? Mono.empty() : mediaStore.delete(key)
                .onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Community picture {0} left in the bucket: {1}", key, error.toString());
                    return Mono.empty();
                });
    }

    /** A public community is visible to all, a private one only to its members. */
    public Mono<Community> find(String userId, String communityId) {
        return communityRepository.findById(communityId, userId)
                .filter(community -> community.visibility() == CommunityVisibility.PUBLIC || community.isMember())
                .switchIfEmpty(notFound());
    }

    public Flux<Community> discover(String userId, String query, String interest) {
        return communityRepository.findPublic(userId, blankToNull(query), normalizedInterest(interest), DISCOVERY_LIMIT);
    }

    public Flux<Community> mine(String userId) {
        return communityRepository.findJoined(userId);
    }

    /** Joining a public community needs no approval; joining twice changes nothing. */
    public Mono<Community> join(String userId, String communityId) {
        return find(userId, communityId)
                .flatMap(community -> community.isMember()
                        ? Mono.just(community)
                        : communityRepository.addMember(communityId, userId, CommunityRole.MEMBER, clock.instant())
                                .then(Mono.defer(() -> find(userId, communityId))));
    }

    /** Takes the user out, handing admin over if needed; deleting the community needs {@code deleteIfLast}. */
    public Mono<CommunityLeave> leave(String userId, String communityId, boolean deleteIfLast) {
        return find(userId, communityId).flatMap(community -> {
            if (!community.isMember()) {
                return Mono.just(CommunityLeave.left());
            }
            if (community.memberCount() <= 1) {
                return deleteIfLast
                        ? deleteWithPicture(communityId).thenReturn(CommunityLeave.deleted())
                        : Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_LAST_MEMBER));
            }
            if (!community.isAdmin()) {
                return communityRepository.removeMember(communityId, userId).thenReturn(CommunityLeave.left());
            }
            Set<String> ineligible = ineligibleAdmins(userId);
            return communityRepository.countAdmins(communityId, ineligible).flatMap(others -> others > 0
                    ? communityRepository.removeMember(communityId, userId).thenReturn(CommunityLeave.left())
                    : handOverAndLeave(communityId, userId, ineligible, deleteIfLast));
        });
    }

    private Mono<CommunityLeave> handOverAndLeave(String communityId, String userId, Set<String> ineligible,
                                                  boolean deleteIfLast) {
        return promoteSuccessor(communityId, ineligible)
                .flatMap(successor -> communityRepository.removeMember(communityId, userId)
                        .thenReturn(CommunityLeave.handedOverTo(successor)))
                // Everyone else left meanwhile, or nobody left can take over: the user is alone after all.
                .switchIfEmpty(Mono.defer(() -> deleteIfLast
                        ? deleteWithPicture(communityId).thenReturn(CommunityLeave.deleted())
                        : Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_LAST_MEMBER))));
    }

    /**
     * Settles a deleted account's community without asking anyone; its membership goes with its node.
     * Runs inside the erasure's transaction, so a community deleted here keeps its pictures: the caller
     * discards them once the transaction has committed.
     */
    public Mono<CommunityLeave> leaveOnErasure(String userId, Community membership) {
        String communityId = membership.communityId();
        if (membership.memberCount() <= 1) {
            return communityRepository.delete(communityId).thenReturn(CommunityLeave.deleted());
        }
        if (!membership.isAdmin()) {
            return Mono.just(CommunityLeave.left());
        }
        Set<String> ineligible = ineligibleAdmins(userId);
        return communityRepository.countAdmins(communityId, ineligible).flatMap(others -> others > 0
                ? Mono.just(CommunityLeave.left())
                : promoteSuccessor(communityId, ineligible)
                        .map(CommunityLeave::handedOverTo)
                        .switchIfEmpty(Mono.defer(() ->
                                communityRepository.delete(communityId).thenReturn(CommunityLeave.deleted()))));
    }

    /** The leaving user and the protected accounts, who can neither keep a community nor take it over. */
    private Set<String> ineligibleAdmins(String leavingUserId) {
        Set<String> ineligible = new HashSet<>(protectedUserIds);
        ineligible.add(leavingUserId);
        return Set.copyOf(ineligible);
    }

    /** Makes the longest-standing eligible member admin; empty when there is none. */
    private Mono<String> promoteSuccessor(String communityId, Set<String> ineligible) {
        return communityRepository.findLongestStandingMember(communityId, ineligible)
                .flatMap(successor -> communityRepository.setRole(communityId, successor, CommunityRole.ADMIN)
                        .thenReturn(successor));
    }

    /** Only members see who the other members are. */
    public Flux<CommunityMember> members(String userId, String communityId) {
        return find(userId, communityId)
                .filter(Community::isMember)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.MEMBERS_ONLY)))
                .flatMapMany(community -> communityRepository.findMembers(communityId));
    }

    /** Several admins are allowed; the last one cannot step down without naming another first. */
    public Mono<Void> changeRole(String userId, String communityId, String memberId, CommunityRole role) {
        return requireAdmin(communityId, userId)
                .then(Mono.defer(() -> communityRepository.roleOf(communityId, memberId)))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.MEMBER_NOT_FOUND)))
                .flatMap(current -> {
                    if (current == role) {
                        return Mono.empty();
                    }
                    Mono<Void> lastAdminGuard = current == CommunityRole.ADMIN
                            ? communityRepository.countAdmins(communityId, Set.of())
                                    .filter(admins -> admins > 1)
                                    .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.LAST_ADMIN)))
                                    .then()
                            : Mono.empty();
                    return lastAdminGuard.then(Mono.defer(() -> communityRepository.setRole(communityId, memberId, role))).then();
                });
    }

    /** An admin removes another member, admin or not; one removes oneself by leaving. */
    public Mono<Void> removeMember(String userId, String communityId, String memberId) {
        if (userId.equals(memberId)) {
            return Mono.error(new BusinessException(BusinessErrorCode.MEMBER_NOT_FOUND));
        }
        return requireAdmin(communityId, userId)
                .then(Mono.defer(() -> communityRepository.removeMember(communityId, memberId)))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.MEMBER_NOT_FOUND)))
                .then();
    }

    /**
     * An admin invites one of their friends, who receives the link by message. The invitation is
     * valid {@link CommunityInvitation#VALIDITY} from now, for that friend only, once.
     */
    public Mono<CommunityInvitation> invite(String userId, String communityId, String inviteeId) {
        if (inviteeId == null || inviteeId.isBlank() || userId.equals(inviteeId)) {
            return Mono.error(new BusinessException(BusinessErrorCode.INVITEE_INVALID));
        }
        Instant now = clock.instant();
        // Each step deferred: nothing is asked of a repository before the previous check passed.
        return requireAdmin(communityId, userId)
                .then(Mono.defer(() -> relationshipRepository.findFriendship(userId, inviteeId)))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.INVITEE_INVALID)))
                .then(Mono.defer(() -> communityRepository.roleOf(communityId, inviteeId).hasElement()))
                .flatMap(alreadyMember -> alreadyMember
                        ? Mono.error(new BusinessException(BusinessErrorCode.INVITEE_INVALID))
                        : communityRepository.saveInvitation(communityId, userId, inviteeId, tokenGenerator.get(),
                                now, now.plus(CommunityInvitation.VALIDITY)));
    }

    public Flux<CommunityInvitation> invitations(String userId, String communityId) {
        return requireAdmin(communityId, userId).thenMany(Flux.defer(() -> communityRepository.findInvitations(communityId)));
    }

    public Mono<Void> revoke(String userId, String communityId, String token) {
        return requireAdmin(communityId, userId)
                .then(Mono.defer(() -> communityRepository.deleteInvitation(communityId, token)))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.INVITATION_NOT_FOUND)))
                .then();
    }

    /** The invitation, for the person it was sent to only; an expired one still reads, to say so. */
    public Mono<CommunityInvitation> invitation(String userId, String token) {
        return communityRepository.findInvitation(token)
                .filter(invitation -> invitation.isFor(userId))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.INVITATION_NOT_FOUND)));
    }

    /** Joins through the invitation, which is then used up. Already a member: it is used up all the same. */
    public Mono<Community> accept(String userId, String token) {
        return invitation(userId, token).flatMap(invitation -> {
            if (invitation.isExpiredAt(clock.instant())) {
                return Mono.error(new BusinessException(BusinessErrorCode.INVITATION_EXPIRED));
            }
            String communityId = invitation.communityId();
            return communityRepository.addMember(communityId, userId, CommunityRole.MEMBER, clock.instant())
                    .then(Mono.defer(() -> communityRepository.deleteInvitation(communityId, token)))
                    .then(Mono.defer(() -> find(userId, communityId)));
        });
    }

    private Mono<Void> requireAdmin(String communityId, String userId) {
        return admin(communityId, userId).then();
    }

    private Mono<Community> admin(String communityId, String userId) {
        return find(userId, communityId)
                .filter(Community::isAdmin)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.COMMUNITY_ADMIN_REQUIRED)));
    }

    private static Mono<CommunityDraft> valid(CommunityDraft draft) {
        return draft != null && draft.isValid()
                ? Mono.just(draft)
                : Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_INVALID));
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(() -> new BusinessException(BusinessErrorCode.COMMUNITY_NOT_FOUND));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizedInterest(String interest) {
        String value = blankToNull(interest);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
