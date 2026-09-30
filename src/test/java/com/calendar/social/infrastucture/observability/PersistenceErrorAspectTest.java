package com.calendar.social.infrastucture.observability;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistenceErrorAspectTest {

    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Signature signature;

    private PersistenceErrorAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new PersistenceErrorAspect();
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("findAllFriends");
    }

    @Test
    @DisplayName("une erreur d'infrastructure dans un Mono devient TechnicalException")
    void translateFailures_shouldMapMonoInfrastructureError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("Bolt closed")));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && ((TechnicalException) error).getErrorCode()
                                == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("une erreur d'infrastructure dans un Flux devient TechnicalException")
    void translateFailures_shouldMapFluxInfrastructureError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        StepVerifier.create((Flux<?>) aspect.translateFailures(joinPoint))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    @DisplayName("une erreur métier traverse sans être réécrite")
    void translateFailures_shouldLetBusinessFailuresThrough() throws Throwable {
        BusinessException businessFailure =
                new BusinessException(BusinessErrorCode.SEND_FRIEND_REQUEST_FAILURE);
        when(joinPoint.proceed()).thenReturn(Mono.error(businessFailure));

        // Un switchIfEmpty de l'adaptateur signifie « pas de relation », pas « la base
        // est tombée » : le masquer en erreur technique donnerait un 500 au lieu d'un 400.
        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error == businessFailure)
                .verify();
    }

    @Test
    @DisplayName("une erreur déjà technique n'est pas enveloppée deux fois")
    void translateFailures_shouldNotRewrapTechnicalFailures() throws Throwable {
        TechnicalException alreadyTranslated = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyTranslated));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error == alreadyTranslated)
                .verify();
    }

    @Test
    @DisplayName("un flux qui réussit passe intact, sans souscription supplémentaire")
    void translateFailures_shouldLeaveSuccessfulResultsAlone() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.just("a", "b"));

        @SuppressWarnings("unchecked")
        Flux<String> wrapped = (Flux<String>) aspect.translateFailures(joinPoint);

        StepVerifier.create(wrapped)
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    @DisplayName("un retour non réactif est renvoyé tel quel")
    void translateFailures_shouldPassThroughNonReactiveReturns() throws Throwable {
        when(joinPoint.proceed()).thenReturn("plain value");

        assertThat(aspect.translateFailures(joinPoint)).isEqualTo("plain value");
    }

    @Test
    @DisplayName("rien n'est souscrit à l'assemblage : l'advice ne déclenche pas l'appel")
    void translateFailures_shouldNotSubscribeEagerly() throws Throwable {
        // Une advice qui souscrirait pour observer l'erreur exécuterait la requête une
        // seconde fois — le piège classique de l'AOP appliqué au réactif.
        Mono<String> lazy = Mono.fromSupplier(() -> {
            throw new AssertionError("le publisher ne doit pas être souscrit ici");
        });
        when(joinPoint.proceed()).thenReturn(lazy);

        Object wrapped = aspect.translateFailures(joinPoint);

        assertThat(wrapped).isInstanceOf(Mono.class);
    }
}
