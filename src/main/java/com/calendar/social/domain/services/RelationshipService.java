package com.calendar.social.domain.services;

import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.ports.RelationshipRepositoryPort;
import com.calendar.social.domain.ports.UserRepositoryPort;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import reactor.core.publisher.Mono;

public class RelationshipService {

    private final UserRepositoryPort userRepositoryPort;
    private final RelationshipRepositoryPort relationshipRepositoryPort;

    public RelationshipService(UserRepositoryPort userRepositoryPort, RelationshipRepositoryPort relationshipRepositoryPort) {
        this.userRepositoryPort = userRepositoryPort;
        this.relationshipRepositoryPort = relationshipRepositoryPort;
    }

    public Mono<UserNodeDTO> sendFriendRequest(Long userId, String userTag) {

        String[] parts = userTag.split("#");

        String userName = parts[0];
        Integer hashtag = Integer.parseInt(parts[1]);

        return userRepositoryPort.existsByUserNameAndHashtag(userName, hashtag)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)))
                .then(userRepositoryPort.sendFriendRequest(userId, userName, hashtag));
    }

    public Mono<UserNodeDTO> acceptFriendRequest(Long userId, Long senderId) {
        return userRepositoryPort.acceptFriendRequest(userId, senderId);
    }

    public Mono<UserNodeDTO> rejectFriendRequest(Long userId, Long senderId) {
        return userRepositoryPort.rejectFriendRequest(userId, senderId);
    }

    public Mono<Void> deleteFriendship(Long userId, Long friendId) {
        return relationshipRepositoryPort.deleteFriendship(userId, friendId).then();
    }
}
