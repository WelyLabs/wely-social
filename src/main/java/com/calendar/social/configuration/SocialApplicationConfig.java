package com.calendar.social.configuration;

import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.services.RelationshipService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialApplicationConfig {

    @Bean
    public RelationshipService relationshipService(
            RelationshipRepository relationshipRepository) {
        return new RelationshipService(relationshipRepository);
    }
}
