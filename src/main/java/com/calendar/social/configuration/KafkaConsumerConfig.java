package com.calendar.social.configuration;

import com.calendar.social.domain.models.UserCreatedEventDTO;
import com.calendar.social.domain.services.RelationshipService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

@Configuration
public class KafkaConsumerConfig {

    private final RelationshipService relationshipService;

    public KafkaConsumerConfig(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @Bean
    public Consumer<Flux<Message<UserCreatedEventDTO>>> userCreated() {
        return flux -> flux
                .concatMap(message -> {
                    UserCreatedEventDTO dto = message.getPayload();

                    return relationshipService.writeUser(dto);
                })
                .subscribe();
    }
}