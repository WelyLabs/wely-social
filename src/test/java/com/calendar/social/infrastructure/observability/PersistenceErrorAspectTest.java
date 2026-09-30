package com.calendar.social.infrastructure.observability;

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
    @DisplayName("an infrastructure failure inside a Mono becomes TechnicalException")
    void translateFailures_shouldMapMonoInfrastructureError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("Bolt closed")));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error instanceof TechnicalException
                        && ((TechnicalException) error).getErrorCode()
                                == TechnicalErrorCode.DATABASE_ERROR)
                .verify();
    }

    @Test
    @DisplayName("an infrastructure failure inside a Flux becomes TechnicalException")
    void translateFailures_shouldMapFluxInfrastructureError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        StepVerifier.create((Flux<?>) aspect.translateFailures(joinPoint))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    @DisplayName("a business error passes through unrewritten")
    void translateFailures_shouldLetBusinessFailuresThrough() throws Throwable {
        BusinessException businessFailure =
                new BusinessException(BusinessErrorCode.SEND_FRIEND_REQUEST_FAILURE);
        when(joinPoint.proceed()).thenReturn(Mono.error(businessFailure));

        // A switchIfEmpty in the adapter means "no such relationship", not "the database
        // is down": masking it as technical would answer 500 where 400 is correct.
        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error == businessFailure)
                .verify();
    }

    @Test
    @DisplayName("an already-technical error is not wrapped twice")
    void translateFailures_shouldNotRewrapTechnicalFailures() throws Throwable {
        TechnicalException alreadyTranslated = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyTranslated));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error == alreadyTranslated)
                .verify();
    }

    @Test
    @DisplayName("a successful stream passes through untouched")
    void translateFailures_shouldLeaveSuccessfulResultsAlone() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.just("a", "b"));

        @SuppressWarnings("unchecked")
        Flux<String> wrapped = (Flux<String>) aspect.translateFailures(joinPoint);

        StepVerifier.create(wrapped)
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    @DisplayName("a non-reactive return is passed through unchanged")
    void translateFailures_shouldPassThroughNonReactiveReturns() throws Throwable {
        when(joinPoint.proceed()).thenReturn("plain value");

        assertThat(aspect.translateFailures(joinPoint)).isEqualTo("plain value");
    }

    @Test
    @DisplayName("nothing is subscribed at assembly: the advice does not trigger the call")
    void translateFailures_shouldNotSubscribeEagerly() throws Throwable {
        // An advice that subscribed in order to observe the error would run the query a
        // second time — the classic trap of applying AOP to reactive code.
        Mono<String> lazy = Mono.fromSupplier(() -> {
            throw new AssertionError("the publisher must not be subscribed here");
        });
        when(joinPoint.proceed()).thenReturn(lazy);

        Object wrapped = aspect.translateFailures(joinPoint);

        assertThat(wrapped).isInstanceOf(Mono.class);
    }
}
