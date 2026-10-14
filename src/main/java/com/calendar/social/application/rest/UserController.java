package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.PersonResponseDTO;
import com.calendar.social.application.rest.mappers.PersonRestMapper;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.services.RelationshipService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("users")
@Validated
public class UserController {

    private static final String BUSINESS_ID = "businessId";

    private final RelationshipService relationshipService;
    private final PersonRestMapper personMapper;

    public UserController(RelationshipService relationshipService, PersonRestMapper personMapper) {
        this.relationshipService = relationshipService;
        this.personMapper = personMapper;
    }

    @GetMapping
    public ResponseEntity<Flux<UserResult>> readAllWithSocialStatus(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String friendshipStatus) {
        String userId = jwt.getClaimAsString(BUSINESS_ID);

        return ResponseEntity.ok()
                .body(relationshipService.readAllWithSocialStatus(userId, friendshipStatus));
    }

    @GetMapping("{userId}")
    public Mono<PersonResponseDTO> findPerson(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return relationshipService.findPerson(jwt.getClaimAsString(BUSINESS_ID), userId)
                .map(personMapper::toResponse);
    }

    @GetMapping("{userId}/mutual")
    public Flux<UserNodeDTO> findMutualFriends(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return relationshipService.findMutualFriends(jwt.getClaimAsString(BUSINESS_ID), userId);
    }
}
