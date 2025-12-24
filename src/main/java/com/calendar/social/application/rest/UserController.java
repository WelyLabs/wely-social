package com.calendar.social.application.rest;

import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.services.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Flux<UserSocialDTO> readAllWithSocialStatus(@RequestHeader("X-Internal-User-Id") Long userId) {
        return userService.readAllWithSocialStatus(userId);
    }
}
