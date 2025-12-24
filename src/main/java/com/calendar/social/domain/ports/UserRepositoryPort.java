package com.calendar.social.domain.ports;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.infrastucture.models.entities.UserNodeEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepositoryPort {

    Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO);

    Flux<UserSocialDTO> findAllWithSocialStatus(Long userId);
}
