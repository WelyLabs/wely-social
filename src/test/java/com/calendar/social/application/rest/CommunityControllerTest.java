package com.calendar.social.application.rest;

import com.calendar.social.application.rest.mappers.CommunityRestMapper;
import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityMember;
import com.calendar.social.domain.models.CommunityRole;
import com.calendar.social.domain.models.CommunityVisibility;
import com.calendar.social.domain.services.CommunityService;
import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String ME = "me";
    private static final String ID = "c1";

    @Mock
    private CommunityService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        lenient().when(jwt.getClaimAsString("businessId")).thenReturn(ME);
        lenient().when(service.pictureUrls(any())).thenReturn(Mono.just(Map.of()));
        CommunityController controller = new CommunityController(service, Mappers.getMapper(CommunityRestMapper.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalErrorHandler())
                .argumentResolvers(configurer -> configurer.addCustomResolver(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                                                        ServerWebExchange exchange) {
                        return Mono.just(jwt);
                    }
                }))
                .build();
    }

    private static Community community(CommunityRole role) {
        return new Community(ID, "Club", "Voitures", List.of("auto"), CommunityVisibility.PUBLIC, NOW, 3, role,
                List.of(), 0, null);
    }

    private static CommunityInvitation invitation(Instant expiresAt) {
        return new CommunityInvitation("tok", ID, "Club", CommunityVisibility.PRIVATE, "admin", "Admin", ME, "me",
                expiresAt.minus(CommunityInvitation.VALIDITY), expiresAt);
    }

    @Test
    void discover_shouldPassTheFiltersOn() {
        when(service.discover(ME, "auto", "rando")).thenReturn(Flux.just(community(null)));

        client.get().uri("/communities?q=auto&interest=rando").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].communityId").isEqualTo(ID)
                .jsonPath("$[0].interests[0]").isEqualTo("auto")
                .jsonPath("$[0].role").doesNotExist();
    }

    @Test
    void mine_shouldListTheCallersCommunities() {
        when(service.mine(ME)).thenReturn(Flux.just(community(CommunityRole.MEMBER)));

        client.get().uri("/communities/mine").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].role").isEqualTo("MEMBER");
    }

    @Test
    void create_shouldAnswerCreated() {
        CommunityDraft draft = new CommunityDraft("Club", "Voitures", List.of("auto"), CommunityVisibility.PUBLIC);
        when(service.create(ME, draft)).thenReturn(Mono.just(community(CommunityRole.ADMIN)));

        client.post().uri("/communities").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Club\",\"description\":\"Voitures\",\"interests\":[\"auto\"],\"visibility\":\"PUBLIC\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.role").isEqualTo("ADMIN");
    }

    @Test
    void create_shouldAnswerTheDomainsRefusal() {
        CommunityDraft draft = new CommunityDraft("ab", null, List.of(), CommunityVisibility.PUBLIC);
        when(service.create(ME, draft)).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_INVALID)));

        client.post().uri("/communities").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"ab\",\"interests\":[],\"visibility\":\"PUBLIC\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-012");
    }

    @Test
    void find_shouldAnswerNotFound_forAPrivateCommunitySeenFromOutside() {
        when(service.find(ME, ID)).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_NOT_FOUND)));

        client.get().uri("/communities/" + ID).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-010");
    }

    @Test
    void find_shouldReturnTheCommunity() {
        when(service.find(ME, ID)).thenReturn(Mono.just(community(null)));

        client.get().uri("/communities/" + ID).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.memberCount").isEqualTo(3);
    }

    @Test
    void update_shouldReturnTheCommunityAsStored() {
        CommunityDraft draft = new CommunityDraft("Club", null, List.of(), CommunityVisibility.PRIVATE);
        when(service.update(ME, ID, draft)).thenReturn(Mono.just(community(CommunityRole.ADMIN)));

        client.put().uri("/communities/" + ID).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Club\",\"interests\":[],\"visibility\":\"PRIVATE\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_shouldAnswerNoContent() {
        when(service.delete(ME, ID)).thenReturn(Mono.empty());

        client.delete().uri("/communities/" + ID).exchange().expectStatus().isNoContent();
    }

    @Test
    void join_shouldReturnTheCommunityAsAMember() {
        when(service.join(ME, ID)).thenReturn(Mono.just(community(CommunityRole.MEMBER)));

        client.post().uri("/communities/" + ID + "/join").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("MEMBER");
    }

    @Test
    void leave_shouldConfirmTheDeletion_whenAsked() {
        when(service.leave(ME, ID, true)).thenReturn(Mono.just(CommunityLeave.deleted()));

        client.post().uri("/communities/" + ID + "/leave").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"deleteIfLast\":true}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.communityDeleted").isEqualTo(true);
    }

    @Test
    void leave_shouldReadAnEmptyBodyAsNoConfirmation() {
        when(service.leave(ME, ID, false)).thenReturn(Mono.just(CommunityLeave.handedOverTo("u")));

        client.post().uri("/communities/" + ID + "/leave").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.newAdminId").isEqualTo("u");
    }

    @Test
    void leave_shouldReadNoBodyAsNoConfirmation() {
        when(service.leave(ME, ID, false)).thenReturn(Mono.just(CommunityLeave.left()));

        client.post().uri("/communities/" + ID + "/leave").exchange().expectStatus().isOk();
    }

    @Test
    void members_shouldBeMembersOnly() {
        when(service.members(ME, ID)).thenReturn(Flux.error(new BusinessException(BusinessErrorCode.MEMBERS_ONLY)));

        client.get().uri("/communities/" + ID + "/members").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-019");
    }

    @Test
    void members_shouldListThem() {
        when(service.members(ME, ID))
                .thenReturn(Flux.just(new CommunityMember("u", "lea", 6000, null, CommunityRole.ADMIN, NOW)));

        client.get().uri("/communities/" + ID + "/members").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].userName").isEqualTo("lea");
    }

    @Test
    void changeRole_shouldAnswerNoContent() {
        when(service.changeRole(ME, ID, "u", CommunityRole.ADMIN)).thenReturn(Mono.empty());

        client.put().uri("/communities/" + ID + "/members/u/role").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"ADMIN\"}")
                .exchange()
                .expectStatus().isNoContent();
        verify(service).changeRole(ME, ID, "u", CommunityRole.ADMIN);
    }

    @Test
    void changeRole_shouldRequireARole() {
        client.put().uri("/communities/" + ID + "/members/u/role").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void removeMember_shouldAnswerNoContent() {
        when(service.removeMember(ME, ID, "u")).thenReturn(Mono.empty());

        client.delete().uri("/communities/" + ID + "/members/u").exchange().expectStatus().isNoContent();
    }

    @Test
    void invite_shouldAnswerCreated_withTheExpiry() {
        when(service.invite(ME, ID, "friend")).thenReturn(Mono.just(invitation(NOW.plus(CommunityInvitation.VALIDITY))));

        client.post().uri("/communities/" + ID + "/invitations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"inviteeId\":\"friend\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.token").isEqualTo("tok")
                .jsonPath("$.expired").isEqualTo(false)
                .jsonPath("$.expiresAt").isEqualTo("2026-10-18T10:00:00Z");
    }

    @Test
    void invite_shouldRequireAnInvitee() {
        client.post().uri("/communities/" + ID + "/invitations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"inviteeId\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void invitations_shouldListThem() {
        when(service.invitations(ME, ID)).thenReturn(Flux.just(invitation(NOW.minusSeconds(1))));

        client.get().uri("/communities/" + ID + "/invitations").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].expired").isEqualTo(true);
    }

    @Test
    void revoke_shouldAnswerNoContent() {
        when(service.revoke(ME, ID, "tok")).thenReturn(Mono.empty());

        client.delete().uri("/communities/" + ID + "/invitations/tok").exchange().expectStatus().isNoContent();
    }

    @Test
    void invitation_shouldShowAnExpiredOne_saySo() {
        when(service.invitation(ME, "tok")).thenReturn(Mono.just(invitation(NOW)));

        client.get().uri("/communities/invitations/tok").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.communityName").isEqualTo("Club")
                .jsonPath("$.expired").isEqualTo(true);
    }

    @Test
    void accept_shouldAnswerGone_onceExpired() {
        when(service.accept(ME, "tok")).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.INVITATION_EXPIRED)));

        client.post().uri("/communities/invitations/tok/accept").exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-014");
    }

    @Test
    void accept_shouldReturnTheCommunityJoined() {
        when(service.accept(ME, "tok")).thenReturn(Mono.just(community(CommunityRole.MEMBER)));

        client.post().uri("/communities/invitations/tok/accept").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.communityId").isEqualTo(ID);
    }

    @Test
    void publicConstructor_shouldUseTheSystemClock() {
        // The constructor Spring calls: nothing to observe but that it builds.
        new CommunityController(service, Mappers.getMapper(CommunityRestMapper.class));
    }

    // --- pictures --------------------------------------------------------------------------------

    private static Community withPicture(String pictureKey) {
        return new Community(ID, "Club", "Voitures", List.of("auto"), CommunityVisibility.PUBLIC, NOW, 3,
                CommunityRole.ADMIN, List.of(), 0, pictureKey);
    }

    private static MultiValueMap<String, HttpEntity<?>> picture(byte[] content) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", content, MediaType.IMAGE_JPEG).filename("photo.jpg");
        return parts.build();
    }

    @Test
    void find_shouldSendTheSignedUrlOfItsPicture() {
        when(service.find(ME, ID)).thenReturn(Mono.just(withPicture("communities/c1/p.jpg")));
        when(service.pictureUrls(List.of(withPicture("communities/c1/p.jpg"))))
                .thenReturn(Mono.just(Map.of("communities/c1/p.jpg", "https://media/p")));

        client.get().uri("/communities/" + ID).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pictureUrl").isEqualTo("https://media/p")
                .jsonPath("$.pictureKey").doesNotExist();
    }

    @Test
    void mine_shouldSignTheWholeListInOneRound() {
        Community first = withPicture("communities/c1/p.jpg");
        Community second = withPicture(null);
        when(service.mine(ME)).thenReturn(Flux.just(first, second));
        when(service.pictureUrls(List.of(first, second)))
                .thenReturn(Mono.just(Map.of("communities/c1/p.jpg", "https://media/p")));

        client.get().uri("/communities/mine").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].pictureUrl").isEqualTo("https://media/p")
                .jsonPath("$[1].pictureUrl").doesNotExist();
        verify(service).pictureUrls(List.of(first, second));
    }

    @Test
    void setPicture_shouldPassTheFileOn_andReturnTheCommunity() {
        byte[] content = {1, 2, 3};
        when(service.setPicture(eq(ME), eq(ID), any())).thenReturn(Mono.just(withPicture("communities/c1/p.jpg")));
        when(service.pictureUrls(any())).thenReturn(Mono.just(Map.of("communities/c1/p.jpg", "https://media/p")));

        client.post().uri("/communities/" + ID + "/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(picture(content)))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.pictureUrl").isEqualTo("https://media/p");
        verify(service).setPicture(eq(ME), eq(ID), aryEq(content));
    }

    @Test
    void setPicture_shouldAnswerBadRequest_whenNoFileIsSent() {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("other", "text");

        client.post().uri("/communities/" + ID + "/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-020");
    }

    @Test
    void setPicture_shouldAnswerForbidden_forAMember() {
        when(service.setPicture(eq(ME), eq(ID), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.COMMUNITY_ADMIN_REQUIRED)));

        client.post().uri("/communities/" + ID + "/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(picture(new byte[]{1})))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SCL-BUS-011");
    }

    @Test
    void setPicture_shouldAnswerBadGateway_whenWelyMediaIsDown() {
        when(service.setPicture(eq(ME), eq(ID), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE)));

        client.post().uri("/communities/" + ID + "/picture")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(picture(new byte[]{1})))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("SCL-TEC-004");
    }

    @Test
    void removePicture_shouldReturnTheCommunityWithoutPicture() {
        when(service.removePicture(ME, ID)).thenReturn(Mono.just(withPicture(null)));

        client.delete().uri("/communities/" + ID + "/picture").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.communityId").isEqualTo(ID)
                .jsonPath("$.pictureUrl").doesNotExist();
    }
}
