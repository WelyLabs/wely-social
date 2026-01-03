package com.calendar.social.domain.services;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserResult;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

public class RelationshipService {

    private final RelationshipRepository relationshipRepository;

    public RelationshipService(RelationshipRepository relationshipRepository) {
        this.relationshipRepository = relationshipRepository;
    }

    public Mono<UserNodeDTO> sendFriendRequest(Long userId, String userTag) {

        String[] parts = userTag.split("#");

        String userName = parts[0];
        Integer hashtag = Integer.parseInt(parts[1]);

        return relationshipRepository.existsByUserNameAndHashtag(userName, hashtag)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)))
                .then(relationshipRepository.sendFriendRequest(userId, userName, hashtag));
    }

    public Mono<UserNodeDTO> acceptFriendRequest(Long userId, Long senderId) {
        return relationshipRepository.acceptFriendRequest(userId, senderId);
    }

    public Mono<UserNodeDTO> rejectFriendRequest(Long userId, Long senderId) {
        return relationshipRepository.rejectFriendRequest(userId, senderId);
    }

    public Mono<Void> deleteFriendship(Long userId, Long friendId) {
        return relationshipRepository.deleteFriendship(userId, friendId).then();
    }

    public Mono<Void> writeUser(UserCreatedEventDTO userCreatedEventDTO) {
        return relationshipRepository.save(userCreatedEventDTO);
    }

    public Flux<UserResult> readAllWithSocialStatus(Long userId, String friendshipsStatus) {

        if (Objects.isNull(friendshipsStatus)) return relationshipRepository.findAllWithSocialStatus(userId).cast(UserResult.class);

        return switch (friendshipsStatus) {
            case "PENDING_INCOMING" -> relationshipRepository.findIncomingRequests(userId).cast(UserResult.class);
            case "PENDING_OUTGOING" -> relationshipRepository.findOutgoingRequests(userId).cast(UserResult.class);
            case "FRIENDS" -> relationshipRepository.findAllFriends(userId).cast(UserResult.class);
            default -> Flux.empty();
        };
    }
}
