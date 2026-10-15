package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.infrastructure.persistence.models.entities.DeletedUserNodeEntity;
import com.calendar.social.infrastructure.persistence.repositories.DeletedUserNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Neo4jDeletedUserMarkersAdapterTest {

    private static final Instant AT = Instant.parse("2026-10-15T14:03:27.123Z");
    private static final ZonedDateTime AT_UTC = ZonedDateTime.of(2026, 10, 15, 14, 3, 27, 123_000_000, ZoneOffset.UTC);

    @Mock
    private DeletedUserNodeRepository repository;

    private Neo4jDeletedUserMarkersAdapter adapter() {
        return new Neo4jDeletedUserMarkersAdapter(repository);
    }

    @Test
    void markDeleted_shouldPassTheDeletedAtInUtc() {
        when(repository.markDeleted("u1", AT_UTC)).thenReturn(Mono.just(new DeletedUserNodeEntity("u1", AT_UTC, null)));

        StepVerifier.create(adapter().markDeleted("u1", AT)).verifyComplete();
    }

    @Test
    void markErased_shouldPassTheErasedAtInUtc() {
        when(repository.markErased("u1", AT_UTC)).thenReturn(Mono.just(new DeletedUserNodeEntity("u1", AT_UTC, AT_UTC)));

        StepVerifier.create(adapter().markErased("u1", AT)).verifyComplete();
    }

    @Test
    void markErased_shouldComplete_whenThereIsNoMarker() {
        when(repository.markErased("u1", AT_UTC)).thenReturn(Mono.empty());

        StepVerifier.create(adapter().markErased("u1", AT)).verifyComplete();
    }

    @Test
    void markDeleted_shouldPropagateTheError_whenTheQueryFails() {
        when(repository.markDeleted("u1", AT_UTC)).thenReturn(Mono.error(new IllegalStateException("Bolt reset")));

        // Translated into DATABASE_ERROR by PersistenceErrorAspect around the adapter, not here.
        StepVerifier.create(adapter().markDeleted("u1", AT)).verifyError(IllegalStateException.class);
    }
}
