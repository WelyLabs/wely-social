package com.calendar.social.infrastructure.users.adapters;

import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.domain.ports.DeletedUsersSource;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.users.mappers.DeletedUsersMapper;
import com.calendar.social.infrastructure.users.models.DeletedUsersPageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Reads wely-users' internal deleted-accounts listing; every failure becomes USERS_UNAVAILABLE. */
@Slf4j
@Component
public class UsersApiDeletedUsersAdapter implements DeletedUsersSource {

    static final String PATH = "/user-service/internal/deleted-users";
    static final String LIMIT_PARAM = "limit";
    static final String AFTER_PARAM = "after";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final DeletedUsersMapper mapper;
    private final boolean configured;
    private final Duration timeout;

    @Autowired
    public UsersApiDeletedUsersAdapter(@Qualifier("usersWebClient") WebClient client, DeletedUsersMapper mapper,
                                       @Value("${app.users-api.url:}") String url,
                                       @Value("${app.users-api.deleted-users-secret:}") String secret) {
        this(client, mapper, url, secret, TIMEOUT);
    }

    /** Takes the timeout, so a test need not wait ten seconds. */
    UsersApiDeletedUsersAdapter(WebClient client, DeletedUsersMapper mapper, String url, String secret,
                                Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.configured = url != null && !url.isBlank() && secret != null && !secret.isBlank();
        this.timeout = timeout;
    }

    @Override
    public Mono<DeletedUsersPage> page(String after, int limit) {
        if (!configured) {
            log.warn("USERS_API_URL or DELETED_USERS_READER_SECRET is not set: deleted accounts cannot be listed");
            return Mono.error(new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE));
        }
        return Mono.defer(() -> client.get()
                        .uri(builder -> {
                            builder.path(PATH).queryParam(LIMIT_PARAM, limit);
                            // A URI variable, so the opaque cursor is encoded whatever it holds.
                            return after == null
                                    ? builder.build()
                                    : builder.queryParam(AFTER_PARAM, "{after}").build(after);
                        })
                        .retrieve()
                        .bodyToMono(DeletedUsersPageResponse.class))
                .timeout(timeout)
                .filter(UsersApiDeletedUsersAdapter::isComplete)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Empty or incomplete deleted-users page")))
                .map(mapper::toDomain)
                .onErrorMap(error -> !(error instanceof TechnicalException), UsersApiDeletedUsersAdapter::unavailable);
    }

    private static boolean isComplete(DeletedUsersPageResponse page) {
        return page.items() != null && page.items().stream().allMatch(item -> item != null && item.userId() != null);
    }

    // The secret travels in a header, which no exception message carries.
    private static TechnicalException unavailable(Throwable error) {
        log.warn("Could not list deleted accounts from wely-users: {}", error.toString());
        return new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE, error);
    }
}
