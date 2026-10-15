package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.ports.DeletedUserMarkers;
import com.calendar.social.infrastructure.persistence.repositories.DeletedUserNodeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;

/** Deletion markers as {@code (:DeletedUser)} nodes, kept apart from {@code (:User)} so no user query lists them. */
@Component
public class Neo4jDeletedUserMarkersAdapter implements DeletedUserMarkers {

    private final DeletedUserNodeRepository repository;

    public Neo4jDeletedUserMarkersAdapter(DeletedUserNodeRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Void> markDeleted(String userId, Instant deletedAt) {
        return repository.markDeleted(userId, deletedAt.atZone(ZoneOffset.UTC)).then();
    }

    @Override
    public Mono<Void> markErased(String userId, Instant erasedAt) {
        return repository.markErased(userId, erasedAt.atZone(ZoneOffset.UTC)).then();
    }
}
