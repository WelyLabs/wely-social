package com.calendar.social.application.rest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.calendar.social.application.rest.mappers.PersonRestMapper;
import com.calendar.social.domain.models.Closeness;
import com.calendar.social.domain.models.FriendRequest;
import com.calendar.social.domain.models.Person;
import com.calendar.social.domain.models.RelationStatus;
import com.calendar.social.domain.models.UserNodeDTO;
import com.calendar.social.domain.models.UserSocialDTO;
import com.calendar.social.domain.services.RelationshipService;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
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

import java.time.Instant;
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

        webTestClient = WebTestClient.bindToController(new UserController(relationshipService, Mappers.getMapper(PersonRestMapper.class)))
                .controllerAdvice(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())))
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

    @Test
    void findPerson_shouldReturnTheFriendshipSeenByTheCaller() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("alice");
        when(relationshipService.findPerson("alice", "bob")).thenReturn(Mono.just(new Person(
                "bob", "bob", 2222, "/b.svg", RelationStatus.FRIENDS, Closeness.CLOSE, Closeness.INTIMATE, true,
                Instant.parse("2025-01-02T03:04:05Z"))));

        webTestClient.get()
                .uri("/users/bob")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("bob")
                .jsonPath("$.userName").isEqualTo("bob")
                .jsonPath("$.hashtag").isEqualTo(2222)
                .jsonPath("$.profilePicUrl").isEqualTo("/b.svg")
                .jsonPath("$.relationStatus").isEqualTo("FRIENDS")
                .jsonPath("$.closeness").isEqualTo("CLOSE")
                .jsonPath("$.proposal").isEqualTo("INTIMATE")
                .jsonPath("$.proposedByFriend").isEqualTo(true)
                .jsonPath("$.since").isEqualTo("2025-01-02T03:04:05Z");
    }

    @Test
    void findPerson_shouldAnswerNullLevels_whenNotFriends() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("alice");
        when(relationshipService.findPerson("alice", "carol")).thenReturn(Mono.just(new Person(
                "carol", "carol", 3333, null, RelationStatus.SENT_BY_THEM, null, null, false, null)));

        webTestClient.get()
                .uri("/users/carol")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.relationStatus").isEqualTo("SENT_BY_THEM")
                .jsonPath("$.closeness").isEmpty()
                .jsonPath("$.since").isEmpty()
                .jsonPath("$.proposedByFriend").isEqualTo(false);
    }

    @Test
    void findPerson_shouldAnswer404_whenTheUserIsUnknown() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("alice");
        when(relationshipService.findPerson("alice", "nobody"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)));

        webTestClient.get()
                .uri("/users/nobody")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-005");
    }

    @Test
    void findMutualFriends_shouldReturnTheSharedFriends() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("alice");
        when(relationshipService.findMutualFriends("alice", "bob"))
                .thenReturn(Flux.just(new UserNodeDTO("carol", "carol", 3333, "/c.svg")));

        webTestClient.get()
                .uri("/users/bob/mutual")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].userId").isEqualTo("carol")
                .jsonPath("$[0].userName").isEqualTo("carol")
                .jsonPath("$[0].hashtag").isEqualTo(3333)
                .jsonPath("$[0].profilePicUrl").isEqualTo("/c.svg");
    }

    @Test
    void findMutualFriends_shouldAnswer404_whenTheUserIsUnknown() {
        when(mockJwt.getClaimAsString("businessId")).thenReturn("alice");
        when(relationshipService.findMutualFriends("alice", "nobody"))
                .thenReturn(Flux.error(new BusinessException(BusinessErrorCode.USER_DOES_NOT_EXIST)));

        webTestClient.get()
                .uri("/users/nobody/mutual")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-005");
    }
}
