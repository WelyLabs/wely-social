package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.FriendRequestDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.services.RelationshipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;

@RestController
@RequestMapping("relationships")
@Validated
public class RelationshipController {

    private final RelationshipService relationshipService;

    public RelationshipController(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @PostMapping("request")
    public Mono<ResponseEntity<UserNodeDTO>> sendFriendRequest(@NotNull @RequestHeader("X-Internal-User-Id") Long userId, @RequestBody @Valid FriendRequestDTO friendRequestDTO) {
        return relationshipService.sendFriendRequest(userId, friendRequestDTO.userTag())
                .flatMap(userNodeDTO -> Mono.just(ResponseEntity.created(URI.create("relationships/request")).body(userNodeDTO)));
    }

    @PutMapping("accept/{senderId}")
    public Mono<ResponseEntity<Void>> acceptFriendRequest(@NotNull @RequestHeader("X-Internal-User-Id") Long userId, @NotNull @PathVariable Long senderId) {
        return relationshipService.acceptFriendRequest(userId, senderId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PutMapping("reject/{senderId}")
    public Mono<ResponseEntity<Void>> rejectFriendRequest(@NotNull @RequestHeader("X-Internal-User-Id") Long userId, @NotNull @PathVariable Long senderId) {
        return relationshipService.rejectFriendRequest(userId, senderId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("{friendId}")
    public Mono<ResponseEntity<Void>> deleteFriendship(@NotNull @RequestHeader("X-Internal-User-Id") Long userId, @NotNull @PathVariable Long friendId) {
        return relationshipService.deleteFriendship(userId, friendId)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
