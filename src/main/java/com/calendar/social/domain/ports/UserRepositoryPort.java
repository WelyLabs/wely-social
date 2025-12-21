package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.infrastucture.models.entities.UserNodeEntity;
import reactor.core.publisher.Mono;

public interface UserRepositoryPort {

    Mono<UserNodeEntity> save(UserCreatedEventDTO userCreatedEventDTO);
}
