package com.calendar.social.application.rest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.calendar.social.application.rest.mappers.FriendshipRestMapper;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendSummary;
import com.calendar.social.domain.models.Friendship;
import com.calendar.social.domain.services.RelationshipService;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.GlobalErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class FriendshipControllerTest {

    @Mock
    private RelationshipService relationshipService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        // Lenient: a request refused by validation never reads the token.
        lenient().when(jwt.getClaimAsString("businessId")).thenReturn("alice");

        client = WebTestClient.bindToController(
                        new FriendshipController(relationshipService, Mappers.getMapper(FriendshipRestMapper.class)))
                .controllerAdvice(new GlobalErrorHandler(new SimpleMeterRegistry()))
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext context,
                                                        ServerWebExchange exchange) {
                        return Mono.just(jwt);
                    }
                }))
                .build();
    }

    @Test
    void listFriendships_shouldReturnTheCallersFriends() {
        when(relationshipService.listFriendships("alice")).thenReturn(Flux.just(
                new FriendSummary("bob", "bob", 2222, null, Closeness.CLOSE, Closeness.INTIMATE, true)));

        client.get().uri("/relationships").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].userId").isEqualTo("bob")
                .jsonPath("$[0].closeness").isEqualTo("CLOSE")
                .jsonPath("$[0].proposal").isEqualTo("INTIMATE")
                .jsonPath("$[0].proposedByFriend").isEqualTo(true);
    }

    @Test
    void findFriendship_shouldReturnTheLevelWithoutTheVersion() {
        when(relationshipService.findFriendship("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.INTIMATE, null, false, 5,
                        java.time.Instant.parse("2025-01-02T03:04:05Z"))));

        client.get().uri("/relationships/bob").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.friendId").isEqualTo("bob")
                .jsonPath("$.since").isEqualTo("2025-01-02T03:04:05Z")
                .jsonPath("$.closeness").isEqualTo("INTIMATE")
                .jsonPath("$.version").doesNotExist();
    }

    @Test
    void findFriendship_shouldAnswer404_whenNotFriends() {
        when(relationshipService.findFriendship("alice", "carol"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.NOT_FRIENDS)));

        client.get().uri("/relationships/carol").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-007");
    }

    @Test
    void requestCloseness_shouldPassTheRequestedLevel() {
        when(relationshipService.requestCloseness("alice", "bob", Closeness.CLOSE))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.FRIEND, Closeness.CLOSE, false, 1, null)));

        client.put().uri("/relationships/bob/closeness")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"closeness\":\"CLOSE\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.proposal").isEqualTo("CLOSE")
                .jsonPath("$.proposedByFriend").isEqualTo(false);
    }

    @Test
    void requestCloseness_shouldRejectAMissingLevel() {
        client.put().uri("/relationships/bob/closeness")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(relationshipService, never()).requestCloseness(any(), any(), any());
    }

    @Test
    void requestCloseness_shouldAnswer409_onAConcurrentChange() {
        when(relationshipService.requestCloseness("alice", "bob", Closeness.INTIMATE))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.CLOSENESS_CONFLICT)));

        client.put().uri("/relationships/bob/closeness")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"closeness\":\"INTIMATE\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void acceptCloseness_shouldReturnTheAgreedLevel() {
        when(relationshipService.acceptCloseness("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.INTIMATE, null, false, 2, null)));

        client.put().uri("/relationships/bob/closeness/accept").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.closeness").isEqualTo("INTIMATE");
    }

    @Test
    void acceptCloseness_shouldAnswer400_whenNothingWasProposed() {
        when(relationshipService.acceptCloseness("alice", "bob"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.NO_CLOSENESS_PROPOSAL)));

        client.put().uri("/relationships/bob/closeness/accept").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-008");
    }

    @Test
    void declineCloseness_shouldReturnTheUnchangedLevel() {
        when(relationshipService.declineCloseness("alice", "bob"))
                .thenReturn(Mono.just(new Friendship("bob", Closeness.FRIEND, null, false, 2, null)));

        client.put().uri("/relationships/bob/closeness/decline").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.closeness").isEqualTo("FRIEND")
                .jsonPath("$.proposal").doesNotExist();
    }
}
