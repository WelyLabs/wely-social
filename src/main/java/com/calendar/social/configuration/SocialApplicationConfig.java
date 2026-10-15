package com.calendar.social.configuration;

import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.FriendshipEventPublisher;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.services.CommunityService;
import com.calendar.social.domain.services.RelationshipService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

@Configuration
public class SocialApplicationConfig {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    public RelationshipService relationshipService(
            RelationshipRepository relationshipRepository,
            FriendshipEventPublisher friendshipEventPublisher) {
        return new RelationshipService(relationshipRepository, friendshipEventPublisher);
    }

    @Bean
    public CommunityService communityService(CommunityRepository communityRepository,
                                             RelationshipRepository relationshipRepository) {
        return new CommunityService(communityRepository, relationshipRepository, Clock.systemUTC(),
                SocialApplicationConfig::invitationToken);
    }

    /**
     * 32 random bytes, URL-safe: an invitation token travels in a link, so it must be impossible to
     * guess rather than merely unique.
     */
    static String invitationToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
