package com.calendar.social.application.rest;

import com.calendar.social.application.rest.dtos.FriendRequestDTO;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.services.RelationshipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationshipControllerTest {

    private WebTestClient webTestClient;

    @Mock
    private RelationshipService relationshipService;

    private Jwt mockJwt;
    private UserNodeDTO userNodeDTO;

    @BeforeEach
    void setUp() {
        mockJwt = mock(Jwt.class);
        userNodeDTO = new UserNodeDTO(UUID.randomUUID().toString(), "testuser", 1234, "avatar.png");

        webTestClient = WebTestClient.bindToController(new RelationshipController(relationshipService))
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
    void sendFriendRequest_Success() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.sendFriendRequest(anyString(), anyString())).thenReturn(Mono.just(userNodeDTO));

        webTestClient.post()
                .uri("/relationships/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FriendRequestDTO("target#5678"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserNodeDTO.class)
                .isEqualTo(userNodeDTO);
    }

    @Test
    void acceptFriendRequest_Success() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.acceptFriendRequest(anyString(), anyString())).thenReturn(Mono.just(userNodeDTO));

        webTestClient.put()
                .uri("/relationships/accept/senderId")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void rejectFriendRequest_Success() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.rejectFriendRequest(anyString(), anyString())).thenReturn(Mono.just(userNodeDTO));

        webTestClient.put()
                .uri("/relationships/reject/senderId")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void deleteFriendship_Success() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("userId");
        when(relationshipService.deleteFriendship(anyString(), anyString())).thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/relationships/friendId")
                .exchange()
                .expectStatus().isNoContent();
    }
}
