package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.FriendRequestDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.services.FriendshipService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("friendships")
public class FriendshipController {

    private final FriendshipService friendshipService;

    public FriendshipController(FriendshipService friendshipService) {
        this.friendshipService = friendshipService;
    }

    @PostMapping("request")
    public Mono<ResponseEntity<UserNodeDTO>> sendFriendRequest(@RequestHeader("X-Internal-User-Id") Long userId, @RequestBody @Valid FriendRequestDTO friendRequestDTO) {
        return friendshipService.sendFriendRequest(userId, friendRequestDTO.userTag()).map(ResponseEntity::ok);
    }
}
