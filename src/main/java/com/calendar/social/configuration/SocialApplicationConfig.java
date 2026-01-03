package com.calendar.social.configuration;

import com.calendar.social.domain.ports.RelationshipPort;
import com.calendar.social.domain.services.RelationshipService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialApplicationConfig {

    @Bean
    public RelationshipService relationshipService(
            RelationshipPort relationshipPort) {
        return new RelationshipService(relationshipPort);
    }
}
