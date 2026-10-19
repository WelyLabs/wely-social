package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.CommunityMemberResponseDTO;
import com.calendar.social.application.rest.dtos.CommunityRequestDTO;
import com.calendar.social.application.rest.dtos.CommunityResponseDTO;
import com.calendar.social.application.rest.dtos.InvitationRequestDTO;
import com.calendar.social.application.rest.dtos.InvitationResponseDTO;
import com.calendar.social.application.rest.dtos.LeaveCommunityRequestDTO;
import com.calendar.social.application.rest.dtos.LeaveCommunityResponseDTO;
import com.calendar.social.application.rest.dtos.RoleRequestDTO;
import com.calendar.social.application.rest.mappers.CommunityRestMapper;
import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.services.CommunityService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

/**
 * Communities. A private one answers 404 SCL-BUS-010 to a non-member on every path, as an unknown
 * one does; a public one shows its purpose to all and its members only to its members (403 SCL-BUS-019).
 */
@RestController
@RequestMapping("communities")
@Validated
public class CommunityController {

    private static final String BUSINESS_ID = "businessId";

    private final CommunityService communityService;
    private final CommunityRestMapper mapper;
    private final Clock clock;

    @Autowired
    public CommunityController(CommunityService communityService, CommunityRestMapper mapper) {
        this(communityService, mapper, Clock.systemUTC());
    }

    CommunityController(CommunityService communityService, CommunityRestMapper mapper, Clock clock) {
        this.communityService = communityService;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The discovery tab: public communities, most members first; {@code q} searches name and description. */
    @GetMapping
    public Flux<CommunityResponseDTO> discover(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String interest) {
        return respond(communityService.discover(userId(jwt), q, interest));
    }

    /** The caller's communities, public and private. */
    @GetMapping("mine")
    public Flux<CommunityResponseDTO> mine(@AuthenticationPrincipal Jwt jwt) {
        return respond(communityService.mine(userId(jwt)));
    }

    /** 400 SCL-BUS-012 when the name, description, interests or visibility break the rules. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CommunityResponseDTO> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CommunityRequestDTO request) {
        return respond(communityService.create(userId(jwt), mapper.toDraft(request)));
    }

    @GetMapping("{communityId}")
    public Mono<CommunityResponseDTO> find(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return respond(communityService.find(userId(jwt), communityId));
    }

    /** Admins only (403 SCL-BUS-011). */
    @PutMapping("{communityId}")
    public Mono<CommunityResponseDTO> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                             @RequestBody CommunityRequestDTO request) {
        return respond(communityService.update(userId(jwt), communityId, mapper.toDraft(request)));
    }

    @DeleteMapping("{communityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return communityService.delete(userId(jwt), communityId);
    }

    /** Public communities only, at once and without approval; joining twice changes nothing. */
    @PostMapping("{communityId}/join")
    public Mono<CommunityResponseDTO> join(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return respond(communityService.join(userId(jwt), communityId));
    }

    /**
     * The last admin hands over to the member who joined the earliest. The only member must confirm
     * with {@code deleteIfLast}, which deletes the community (409 SCL-BUS-016 without it).
     */
    @PostMapping("{communityId}/leave")
    public Mono<LeaveCommunityResponseDTO> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                                 @RequestBody(required = false) LeaveCommunityRequestDTO request) {
        boolean deleteIfLast = request != null && Boolean.TRUE.equals(request.deleteIfLast());
        return communityService.leave(userId(jwt), communityId, deleteIfLast).map(mapper::toResponse);
    }

    /**
     * Admins only. Multipart {@code file}, cropped to a 512 px square: 400 SCL-BUS-020 for anything but
     * an image, 413 SCL-BUS-021 above 10 MB, 502 SCL-TEC-004 when wely-media cannot store it.
     */
    @PostMapping(value = "{communityId}/picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<CommunityResponseDTO> setPicture(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                                 @RequestBody Mono<MultiValueMap<String, Part>> form) {
        return respond(Uploads.fileOf(form)
                .flatMap(content -> communityService.setPicture(userId(jwt), communityId, content)));
    }

    /** Admins only; the community goes back to its initials. */
    @DeleteMapping("{communityId}/picture")
    public Mono<CommunityResponseDTO> removePicture(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return respond(communityService.removePicture(userId(jwt), communityId));
    }

    @GetMapping("{communityId}/members")
    public Flux<CommunityMemberResponseDTO> members(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return communityService.members(userId(jwt), communityId).map(mapper::toResponse);
    }

    /** Admins only. The last admin cannot step down (409 SCL-BUS-017). */
    @PutMapping("{communityId}/members/{memberId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                 @PathVariable String memberId, @RequestBody @Valid RoleRequestDTO request) {
        return communityService.changeRole(userId(jwt), communityId, memberId, request.role());
    }

    @DeleteMapping("{communityId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                   @PathVariable String memberId) {
        return communityService.removeMember(userId(jwt), communityId, memberId);
    }

    /** Admins invite one of their friends (400 SCL-BUS-015 otherwise); the link goes to them by message. */
    @PostMapping("{communityId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<InvitationResponseDTO> invite(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                                              @RequestBody @Valid InvitationRequestDTO request) {
        return communityService.invite(userId(jwt), communityId, request.inviteeId())
                .map(invitation -> mapper.toResponse(invitation, clock.instant()));
    }

    /** Admins only: invitations not used nor revoked yet, expired ones included. */
    @GetMapping("{communityId}/invitations")
    public Flux<InvitationResponseDTO> invitations(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId) {
        return communityService.invitations(userId(jwt), communityId)
                .map(invitation -> mapper.toResponse(invitation, clock.instant()));
    }

    @DeleteMapping("{communityId}/invitations/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String communityId,
                             @PathVariable String token) {
        return communityService.revoke(userId(jwt), communityId, token);
    }

    /** For the person invited only (404 SCL-BUS-013 for anyone else); an expired one reads with {@code expired}. */
    @GetMapping("invitations/{token}")
    public Mono<InvitationResponseDTO> invitation(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return communityService.invitation(userId(jwt), token)
                .map(invitation -> mapper.toResponse(invitation, clock.instant()));
    }

    /** Joins and uses the invitation up; 410 SCL-BUS-014 once the 3 days are over. */
    @PostMapping("invitations/{token}/accept")
    public Mono<CommunityResponseDTO> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return respond(communityService.accept(userId(jwt), token));
    }

    /** One signing round for the whole list, so a page costs one call to wely-media, not one per community. */
    private Flux<CommunityResponseDTO> respond(Flux<Community> communities) {
        return communities.collectList()
                .flatMapMany(list -> communityService.pictureUrls(list)
                        .flatMapIterable(urls -> list.stream().map(community -> mapper.toResponse(community, urls)).toList()));
    }

    private Mono<CommunityResponseDTO> respond(Mono<Community> community) {
        return community.flatMap(found -> communityService.pictureUrls(List.of(found))
                .map(urls -> mapper.toResponse(found, urls)));
    }

    private static String userId(Jwt jwt) {
        return jwt.getClaimAsString(BUSINESS_ID);
    }
}
