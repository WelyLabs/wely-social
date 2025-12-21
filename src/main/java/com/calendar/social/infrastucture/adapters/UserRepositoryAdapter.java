package com.calendar.social.infrastucture.adapters;

import com.calendar.social.domain.ports.UserRepositoryPort;
import com.calendar.social.infrastucture.models.entities.UserNodeEntity;
import com.calendar.social.infrastucture.repositories.UserNodeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserNodeRepository userNodeRepository;

    public UserRepositoryAdapter(UserNodeRepository userNodeRepository) {
        this.userNodeRepository = userNodeRepository;
    }

    public Mono<UserNodeEntity> save() {

    }
}
