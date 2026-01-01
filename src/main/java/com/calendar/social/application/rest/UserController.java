package com.calendar.social.application.rest;

import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.services.UserService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<Flux<UserResult>> readAllWithSocialStatus(
            @NotBlank @RequestHeader("X-Internal-User-Id") Long userId,
            @RequestParam(required = false) String friendshipStatus) {
        return ResponseEntity.ok()
                .body(userService.readAllWithSocialStatus(userId, friendshipStatus));
    }
}
