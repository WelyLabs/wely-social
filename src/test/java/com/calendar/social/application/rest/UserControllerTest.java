package com.calendar.social.application.rest;

import com.calendar.social.domain.models.FriendRequest;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.services.RelationshipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private WebTestClient webTestClient;

    @Mock
    private RelationshipService relationshipService;

    private Jwt mockJwt;

    @BeforeEach
    void setUp() {
        mockJwt = mock(Jwt.class);

        webTestClient = WebTestClient.bindToController(new UserController(relationshipService))
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                            ServerWebExchange exchange) {
                        return Mono.just(mockJwt);
                    }
                }))
                .build();
    }

    @Test
    void readAllWithSocialStatus_Success() {
        UserNodeDTO userNodeDTO = new UserNodeDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.readAllWithSocialStatus(anyString(), any())).thenReturn(Flux.just(userNodeDTO));

        webTestClient.get()
                .uri("/users?friendshipStatus=FRIENDS")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserNodeDTO.class)
                .hasSize(1)
                .contains(userNodeDTO);
    }

    @Test
    void readAllWithSocialStatus_shouldExposeTheEventOfAPendingRequest() {
        FriendRequest request = new FriendRequest("u2", "testuser", 1234, null, "event-1");
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.readAllWithSocialStatus("userId", "PENDING_INCOMING")).thenReturn(Flux.just(request));

        webTestClient.get()
                .uri("/users?friendshipStatus=PENDING_INCOMING")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].userId").isEqualTo("u2")
                .jsonPath("$[0].hashtag").isEqualTo(1234)
                .jsonPath("$[0].viaEventId").isEqualTo("event-1");
    }

    @Test
    void readAllWithSocialStatus_shouldExposeTheHashtagAndEventInTheFullListing() {
        UserSocialDTO user = new UserSocialDTO("u2", "testuser", 1234, null, "SENT_BY_THEM", "event-1");
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.readAllWithSocialStatus("userId", null)).thenReturn(Flux.just(user));

        webTestClient.get()
                .uri("/users")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].relationStatus").isEqualTo("SENT_BY_THEM")
                .jsonPath("$[0].hashtag").isEqualTo(1234)
                .jsonPath("$[0].viaEventId").isEqualTo("event-1");
    }
}
