package com.calendar.social.domain.services;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.UserRepositoryPort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;

    public UserService(UserRepositoryPort userRepositoryPort) {
        this.userRepositoryPort = userRepositoryPort;
    }

    public Mono<Void> writeUser(UserCreatedEventDTO userCreatedEventDTO) {
        return userRepositoryPort.save(userCreatedEventDTO);
    }

    public Flux<UserSocialDTO> readAllWithSocialStatus(Long userId) {
        return userRepositoryPort.findAllWithSocialStatus(userId);
    }
}
