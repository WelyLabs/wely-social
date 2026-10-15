package com.calendar.social.configuration;

import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.FriendshipEventPublisher;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.TransactionRunner;
import com.calendar.social.domain.services.CommunityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        assertThat(config.communityService(mock(CommunityRepository.class), mock(RelationshipRepository.class),
                "89a380b8-f66a-44d0-b9d9-616241080430")).isNotNull();
    }

    @Test
    void accountErasureService_shouldBuildTheDomainServiceFromItsPorts() {
        CommunityService communityService = config.communityService(mock(CommunityRepository.class),
                mock(RelationshipRepository.class), "");

        assertThat(config.accountErasureService(mock(DeletedUserMarkers.class), mock(CommunityRepository.class),
                communityService, mock(RelationshipRepository.class), mock(TransactionRunner.class))).isNotNull();
    }

    @Test
    @DisplayName("the protected ids are read in the JWT's canonical form, spaces and empty entries ignored")
    void protectedUserIds_shouldNormaliseEachId() {
        assertThat(SocialApplicationConfig.protectedUserIds(
                " 89A380B8-F66A-44D0-B9D9-616241080430 ,, 1c2a9b0e-0000-4000-8000-000000000001 "))
                .containsExactlyInAnyOrder("89a380b8-f66a-44d0-b9d9-616241080430", "1c2a9b0e-0000-4000-8000-000000000001");
    }

    @Test
    void protectedUserIds_shouldBeEmpty_whenNothingIsConfigured() {
        assertThat(SocialApplicationConfig.protectedUserIds("")).isEmpty();
        assertThat(SocialApplicationConfig.protectedUserIds(null)).isEmpty();
    }

    @Test
    @DisplayName("a typo fails the startup rather than silently protecting nobody")
    void protectedUserIds_shouldRefuseAMalformedId() {
        assertThatThrownBy(() -> SocialApplicationConfig.protectedUserIds("camille"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a truncated id that UUID.fromString would still parse fails the startup")
    void protectedUserIds_shouldRefuseANonCanonicalId_whenItIsTruncated() {
        assertThatThrownBy(() -> SocialApplicationConfig.protectedUserIds(
                "89a380b8-f66a-44d0-b9d9-616241080430,89a380b8-f66a-44d0-b9d9-61624108043"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("89a380b8-f66a-44d0-b9d9-61624108043");
    }

    @Test
    void protectedUserIds_shouldRefuseANonCanonicalId_whenAGroupIsShort() {
        assertThatThrownBy(() -> SocialApplicationConfig.protectedUserIds("1-2-3-4-5"))
                .isInstanceOf(IllegalArgumentException.class);
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
