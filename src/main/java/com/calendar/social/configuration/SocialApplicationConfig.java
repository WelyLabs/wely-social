package com.calendar.social.configuration;

import com.calendar.social.domain.ports.CommunityRepository;
import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.domain.ports.DeletedUsersSource;
import com.calendar.social.domain.ports.FriendshipEventOutbox;
import com.calendar.social.domain.ports.MediaStore;
import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.domain.ports.SocialActivity;
import com.calendar.social.domain.ports.TransactionRunner;
import com.calendar.social.domain.services.AccountErasureService;
import com.calendar.social.domain.services.AccountReconciliationService;
import com.calendar.social.domain.services.CommunityService;
import com.calendar.social.domain.services.RelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class SocialApplicationConfig {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    public RelationshipService relationshipService(
            RelationshipRepository relationshipRepository,
            FriendshipEventOutbox friendshipEventOutbox,
            TransactionRunner transactions,
            SocialActivity activity) {
        return new RelationshipService(relationshipRepository, friendshipEventOutbox, transactions, activity);
    }

    @Bean
    public CommunityService communityService(
            CommunityRepository communityRepository,
            RelationshipRepository relationshipRepository,
            MediaStore mediaStore,
            SocialActivity activity,
            @Value("${app.account-deletion.protected-user-ids:}") String protectedUserIds) {
        Set<String> protectedIds = protectedUserIds(protectedUserIds);
        log.info("Community hand-overs skip {} protected account(s)", protectedIds.size());
        return new CommunityService(communityRepository, relationshipRepository, mediaStore, Clock.systemUTC(),
                SocialApplicationConfig::invitationToken, protectedIds, activity);
    }

    @Bean
    public AccountErasureService accountErasureService(DeletedUserMarkers markers,
                                                       CommunityRepository communityRepository,
                                                       CommunityService communityService,
                                                       RelationshipRepository relationshipRepository,
                                                       FriendshipEventOutbox friendshipEventOutbox,
                                                       TransactionRunner transactions) {
        return new AccountErasureService(markers, communityRepository, communityService, relationshipRepository,
                friendshipEventOutbox, transactions, Clock.systemUTC());
    }

    @Bean
    public AccountReconciliationService accountReconciliationService(
            DeletedUsersSource deletedUsersSource,
            DeletedUserMarkers markers,
            AccountErasureService accountErasureService,
            @Value("${app.account-erasure.reconciliation.page-size:500}") int pageSize,
            @Value("${app.account-erasure.reconciliation.recent-window:48h}") Duration recentWindow) {
        return new AccountReconciliationService(deletedUsersSource, markers, accountErasureService, Clock.systemUTC(),
                pageSize, recentWindow);
    }

    /**
     * 32 random bytes, URL-safe: an invitation token travels in a link, so it must be impossible to
     * guess rather than merely unique.
     */
    static String invitationToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Parses comma-separated canonical UUIDs into their lowercase form, failing on any other id. */
    static Set<String> protectedUserIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .map(SocialApplicationConfig::canonicalUuid)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String canonicalUuid(String id) {
        // UUID.fromString also accepts short groups, so a truncated id would parse into another UUID.
        String canonical = UUID.fromString(id).toString();
        if (!canonical.equalsIgnoreCase(id)) {
            throw new IllegalArgumentException("Protected user id is not a canonical UUID: " + id);
        }
        return canonical;
    }
}
