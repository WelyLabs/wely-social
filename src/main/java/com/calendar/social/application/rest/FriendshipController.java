package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.ClosenessRequestDTO;
import com.calendar.social.application.rest.dtos.FriendshipResponseDTO;
import com.calendar.social.application.rest.mappers.FriendshipRestMapper;
import com.calendar.social.domain.models.FriendOfFriend;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.services.RelationshipService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Accepted friendships and their closeness. A step up is a proposal the friend accepts or
 * declines; a step down applies at once.
 */
@RestController
@RequestMapping("relationships")
@Validated
public class FriendshipController {

    private static final String BUSINESS_ID = "businessId";

    private final RelationshipService relationshipService;
    private final FriendshipRestMapper mapper;

    public FriendshipController(RelationshipService relationshipService, FriendshipRestMapper mapper) {
        this.relationshipService = relationshipService;
        this.mapper = mapper;
    }

    @GetMapping
    public Flux<FriendSummary> listFriendships(@AuthenticationPrincipal Jwt jwt) {
        return relationshipService.listFriendships(jwt.getClaimAsString(BUSINESS_ID));
    }

    /**
     * Who this friend is friends with, for a CLOSE or INTIMATE friend of theirs: 404 SCL-BUS-007
     * when they are not friends, 403 SCL-BUS-022 when not close enough. No closeness is returned --
     * how close two other people are is theirs, not the caller's to read.
     */
    @GetMapping("{friendId}/friends")
    public Flux<FriendOfFriend> listFriendsOfFriend(@AuthenticationPrincipal Jwt jwt, @PathVariable String friendId) {
        return relationshipService.listFriendsOf(jwt.getClaimAsString(BUSINESS_ID), friendId);
    }

    /** Also what wely-events asks to know how much of a friend's calendar the caller may see. */
    @GetMapping("{friendId}")
    public Mono<FriendshipResponseDTO> findFriendship(@AuthenticationPrincipal Jwt jwt, @PathVariable String friendId) {
        return relationshipService.findFriendship(jwt.getClaimAsString(BUSINESS_ID), friendId)
                .map(mapper::toResponse);
    }

    @PutMapping("{friendId}/closeness")
    public Mono<FriendshipResponseDTO> requestCloseness(@AuthenticationPrincipal Jwt jwt, @PathVariable String friendId,
                                                        @RequestBody @Valid ClosenessRequestDTO request) {
        return relationshipService.requestCloseness(jwt.getClaimAsString(BUSINESS_ID), friendId, request.closeness())
                .map(mapper::toResponse);
    }

    @PutMapping("{friendId}/closeness/accept")
    public Mono<FriendshipResponseDTO> acceptCloseness(@AuthenticationPrincipal Jwt jwt, @PathVariable String friendId) {
        return relationshipService.acceptCloseness(jwt.getClaimAsString(BUSINESS_ID), friendId)
                .map(mapper::toResponse);
    }

    @PutMapping("{friendId}/closeness/decline")
    public Mono<FriendshipResponseDTO> declineCloseness(@AuthenticationPrincipal Jwt jwt, @PathVariable String friendId) {
        return relationshipService.declineCloseness(jwt.getClaimAsString(BUSINESS_ID), friendId)
                .map(mapper::toResponse);
    }
}
