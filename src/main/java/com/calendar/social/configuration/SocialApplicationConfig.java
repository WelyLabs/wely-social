package com.calendar.social.configuration;

import com.calendar.social.domain.ports.UserRepositoryPort;
import com.calendar.social.domain.services.FriendshipService;
import com.calendar.social.domain.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialApplicationConfig {

    @Bean
    public FriendshipService relationshipService(UserRepositoryPort userRepositoryPort) {
        return new FriendshipService(userRepositoryPort);
    }

    @Bean
    public UserService userService(UserRepositoryPort userRepositoryPort) {
        return new UserService(userRepositoryPort);
    }
}
