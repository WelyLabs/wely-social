package com.calendar.social.configuration;

import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.FriendshipEventPublisher;
import com.calendar.social.domain.ports.RelationshipRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SocialApplicationConfigTest {

    private final SocialApplicationConfig config = new SocialApplicationConfig();

    @Test
    void relationshipService_shouldBuildTheDomainServiceFromItsPorts() {
        assertThat(config.relationshipService(mock(RelationshipRepository.class), mock(FriendshipEventPublisher.class)))
                .isNotNull();
    }

    @Test
    void communityService_shouldBuildTheDomainServiceFromItsPorts() {
        assertThat(config.communityService(mock(CommunityRepository.class), mock(RelationshipRepository.class)))
                .isNotNull();
    }

    @Test
    void invitationToken_shouldBeUrlSafeAndLongEnoughToBeUnguessable() {
        String token = SocialApplicationConfig.invitationToken();

        // 32 bytes in unpadded base64url: 43 characters, all usable in a path segment.
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void invitationToken_shouldDifferEveryTime() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(SocialApplicationConfig.invitationToken());
        }

        assertThat(tokens).hasSize(100);
    }
}
