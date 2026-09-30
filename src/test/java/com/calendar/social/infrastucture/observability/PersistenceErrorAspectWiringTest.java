package com.calendar.social.infrastucture.observability;

import com.calendar.social.domain.ports.RelationshipRepository;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastucture.persistence.adapters.Neo4jRelationshipRepositoryAdapter;
import com.calendar.social.infrastucture.persistence.mappers.RelationshipMapper;
import com.calendar.social.infrastucture.persistence.mappers.UserNodeMapper;
import com.calendar.social.infrastucture.persistence.repositories.UserNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the pointcut actually matches the adapter.
 *
 * <p>{@code PersistenceErrorAspectTest} checks the advice logic in isolation, which says
 * nothing about whether it is ever applied — a typo in the package name of the pointcut
 * expression compiles, starts, and silently advises nothing. This test closes that gap.
 *
 * <p>A hand-built context rather than {@code @SpringBootTest}: this service binds a Kafka
 * consumer, and the binder polls an absent broker while the context starts, so a Boot
 * context never finishes booting here. Three beans and
 * {@code @EnableAspectJAutoProxy} are enough.
 */
@SpringJUnitConfig(PersistenceErrorAspectWiringTest.TestConfig.class)
class PersistenceErrorAspectWiringTest {

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        static final UserNodeRepository userNodeRepository = mock(UserNodeRepository.class);

        @Bean
        PersistenceErrorAspect persistenceErrorAspect() {
            return new PersistenceErrorAspect();
        }

        @Bean
        RelationshipRepository relationshipRepository() {
            return new Neo4jRelationshipRepositoryAdapter(
                    mock(com.calendar.social.infrastucture.persistence.repositories.RelationshipRepository.class),
                    mock(RelationshipMapper.class),
                    userNodeRepository,
                    mock(UserNodeMapper.class));
        }
    }

    @Autowired
    private RelationshipRepository adapter;

    @Test
    @DisplayName("l'adaptateur est bien proxifié par l'aspect")
    void adapter_shouldBeAdvised() {
        assertThat(AopUtils.isAopProxy(adapter))
                .as("l'adaptateur devrait être un proxy AOP")
                .isTrue();
    }

    @Test
    @DisplayName("à travers le proxy, une erreur Neo4j ressort en TechnicalException")
    void adapter_shouldTranslateThroughTheProxy() {
        when(TestConfig.userNodeRepository.findAllFriends(anyString()))
                .thenReturn(Flux.error(new RuntimeException("Bolt connection reset")));

        StepVerifier.create(adapter.findAllFriends("user-1"))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && ((TechnicalException) error).getErrorCode()
                                == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("le pointcut couvre toutes les méthodes publiques, pas seulement la première")
    void adapter_shouldTranslateEveryMethod() {
        when(TestConfig.userNodeRepository.findAllWithSocialStatus(anyString()))
                .thenReturn(Flux.error(new RuntimeException("Bolt connection reset")));

        StepVerifier.create(adapter.findAllWithSocialStatus("user-1"))
                .expectError(TechnicalException.class)
                .verify();
    }
}
