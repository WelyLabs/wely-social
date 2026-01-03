package com.calendar.social.application.rest;

import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.services.RelationshipService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("users")
@Validated
public class UserController {

    private final RelationshipService relationshipService;

    public UserController(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @GetMapping
    public ResponseEntity<Flux<UserResult>> readAllWithSocialStatus(
            @NotNull @RequestHeader("X-Internal-User-Id") Long userId,
            @RequestParam(required = false) String friendshipStatus) {
        return ResponseEntity.ok()
                .body(relationshipService.readAllWithSocialStatus(userId, friendshipStatus));
    }
}
