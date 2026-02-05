package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.FriendRequestDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.services.RelationshipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("relationships")
@Validated
public class RelationshipController {

    private final RelationshipService relationshipService;

    public RelationshipController(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @PostMapping("request")
    public Mono<ResponseEntity<UserNodeDTO>> sendFriendRequest(@AuthenticationPrincipal Jwt jwt,
                                                                   @RequestBody @Valid FriendRequestDTO friendRequestDTO) {
        String userId = jwt.getClaimAsString("businessId");

        return relationshipService.sendFriendRequest(userId, friendRequestDTO.userTag())
                .flatMap(userNodeDTO -> Mono.just(ResponseEntity.created(URI.create("relationships/request")).body(userNodeDTO)));
    }

    @PutMapping("accept/{senderId}")
    public Mono<ResponseEntity<Void>> acceptFriendRequest(@AuthenticationPrincipal Jwt jwt, @NotNull @PathVariable String senderId) {
        String userId = jwt.getClaimAsString("businessId");

        return relationshipService.acceptFriendRequest(userId, senderId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PutMapping("reject/{senderId}")
    public Mono<ResponseEntity<Void>> rejectFriendRequest(@AuthenticationPrincipal Jwt jwt, @NotNull @PathVariable String senderId) {
        String userId = jwt.getClaimAsString("businessId");

        return relationshipService.rejectFriendRequest(userId, senderId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("{friendId}")
    public Mono<ResponseEntity<Void>> deleteFriendship(@AuthenticationPrincipal Jwt jwt, @NotNull @PathVariable String friendId) {
        String userId = jwt.getClaimAsString("businessId");

        return relationshipService.deleteFriendship(userId, friendId)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
