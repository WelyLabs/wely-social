package com.calendar.social.domain.services;

import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.ports.UserRepositoryPort;
import reactor.core.publisher.Mono;

public class FriendshipService {

    private final UserRepositoryPort userRepositoryPort;

    public FriendshipService(UserRepositoryPort userRepositoryPort) {
        this.userRepositoryPort = userRepositoryPort;
    }

    public Mono<UserNodeDTO> sendFriendRequest(Long userId, String userTag) {

        String[] parts = userTag.split("#");

        String userName = parts[0];
        Integer hashtag = Integer.parseInt(parts[1]);

        return userRepositoryPort.sendFriendRequest(userId, userName, hashtag);
    }
}
