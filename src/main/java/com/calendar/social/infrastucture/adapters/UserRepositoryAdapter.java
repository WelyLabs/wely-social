package com.calendar.social.infrastucture.adapters;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.ports.UserRepositoryPort;
import com.calendar.social.infrastucture.mappers.UserNodeMapper;
import com.calendar.social.infrastucture.models.dtos.UserSocialDBDTO;
import com.calendar.social.infrastucture.repositories.UserNodeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserNodeRepository userNodeRepository;
    private final UserNodeMapper userNodeMapper;

    public UserRepositoryAdapter(UserNodeRepository userNodeRepository, UserNodeMapper userNodeMapper) {
        this.userNodeRepository = userNodeRepository;
        this.userNodeMapper = userNodeMapper;
    }

    public Mono<Void> save(UserCreatedEventDTO userCreatedEventDTO) {
        return userNodeRepository.save(userNodeMapper.toUserNodeEntity(userCreatedEventDTO)).then();
    }

    public Flux<UserSocialDTO> findAllWithSocialStatus(Long userId) {
        return userNodeRepository.findAllWithSocialStatus(userId).map(userNodeMapper::toUserSocialDTO);
    }
}
