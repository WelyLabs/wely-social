package com.calendar.social.infrastructure.observability;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the TechnicalException out of a Mono carries the driver failure as its cause")
    void translateFailures_shouldKeepTheMonoFailureAsCause() throws Throwable {
        RuntimeException driverFailure = new RuntimeException("Bolt closed");
        when(joinPoint.proceed()).thenReturn(Mono.error(driverFailure));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TechnicalException.class)
                        .cause().isSameAs(driverFailure)).verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a real driver failure kept as cause carries no password into the logged trace")
    void translateFailures_shouldKeepNoPasswordInTheTrace_ofARealDriverFailure() throws Throwable {
        // Regression guard: the driver gets its password from AuthTokens, never from the URI, and
        // its connection errors name the address only. If that changes, the ERROR log would leak it.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Throwable driverFailure;
        try (Driver driver = GraphDatabase.driver("bolt://127.0.0.1:" + closedPort,
                AuthTokens.basic("neo4j", "s3cr3t"),
                Config.builder().withConnectionTimeout(1, TimeUnit.SECONDS).build())) {
            driverFailure = catchThrowable(driver::verifyConnectivity);
        }
        when(joinPoint.proceed()).thenReturn(Mono.error(driverFailure));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorSatisfies(error -> {
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    assertThat(error.getCause()).isSameAs(driverFailure);
                    assertThat(trace.toString()).contains("127.0.0.1:" + closedPort).doesNotContain("s3cr3t");
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an infrastructure failure inside a Flux becomes TechnicalException")
    void translateFailures_shouldMapFluxInfrastructureError() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        StepVerifier.create((Flux<?>) aspect.translateFailures(joinPoint))
                .expectError(TechnicalException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the TechnicalException out of a Flux carries the driver failure as its cause")
    void translateFailures_shouldKeepTheFluxFailureAsCause() throws Throwable {
        IllegalStateException driverFailure = new IllegalStateException("pool exhausted");
        when(joinPoint.proceed()).thenReturn(Flux.error(driverFailure));

        StepVerifier.create((Flux<?>) aspect.translateFailures(joinPoint))
                .expectErrorSatisfies(error -> assertThat(error.getCause()).isSameAs(driverFailure)).verify(Duration.ofSeconds(10));
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
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an already-technical error is not wrapped twice")
    void translateFailures_shouldNotRewrapTechnicalFailures() throws Throwable {
        TechnicalException alreadyTranslated = new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
        when(joinPoint.proceed()).thenReturn(Mono.error(alreadyTranslated));

        StepVerifier.create((Mono<?>) aspect.translateFailures(joinPoint))
                .expectErrorMatches(error -> error == alreadyTranslated)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a successful stream passes through untouched")
    void translateFailures_shouldLeaveSuccessfulResultsAlone() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Flux.just("a", "b"));

        @SuppressWarnings("unchecked")
        Flux<String> wrapped = (Flux<String>) aspect.translateFailures(joinPoint);

        StepVerifier.create(wrapped)
                .expectNext("a", "b")
                .expectComplete().verify(Duration.ofSeconds(10));
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
