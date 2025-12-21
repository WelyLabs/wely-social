package com.calendar.social.application.messaging;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.services.UserService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class KafkaUserEventsListener {

    private static final String USER_CREATED_TOPIC = "USER_CREATED";

    private final UserService userService;
    private final ObjectMapper objectMapper;

    public KafkaUserEventsListener(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = USER_CREATED_TOPIC)
    Mono<Void> listen(String message) {
        return userService.writeUser(objectMapper.readValue(message, UserCreatedEventDTO.class));
    }
}
