package com.calendar.social.infrastructure.reconciliation;

import com.calendar.social.domain.models.ReconciliationReport;
import com.calendar.social.domain.services.AccountReconciliationService;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletedUsersReconciliationLoopTest {

    private static final Duration INTERVAL = Duration.ofHours(24);
    private static final Duration RETRY_INTERVAL = Duration.ofHours(1);
    private static final Duration SHORT = Duration.ofMillis(20);
    private static final Duration LONG = Duration.ofHours(1);
    private static final String KILL_SWITCH = "app.account-erasure.reconciliation.enabled";
    // Wall-clock bound, so a pass that is never cut fails the test instead of hanging it.
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private AccountReconciliationService reconciliation;

    // Boot's conversion service, as in the application, so the "1m" / "24h" / "1h" defaults are parsed too.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(DeletedUsersReconciliationLoop.class)
            .withBean(AccountReconciliationService.class, () -> mock(AccountReconciliationService.class));

    private DeletedUsersReconciliationLoop loop(Duration initialDelay, Duration interval, Duration retryInterval) {
        return new DeletedUsersReconciliationLoop(reconciliation, initialDelay, interval, retryInterval);
    }

    @Test
    void passOnce_shouldWaitTheInterval_whenThePassIsClean() {
        when(reconciliation.reconcile()).thenReturn(Mono.just(new ReconciliationReport(3, 1, 0)));

        StepVerifier.create(loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce()).expectNext(INTERVAL).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void passOnce_shouldRetryAfterTheRetryInterval_whenThePassFails() {
        when(reconciliation.reconcile())
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE)));

        StepVerifier.create(loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectNext(RETRY_INTERVAL)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void passOnce_shouldRetryAfterTheRetryInterval_whenAnErasureFailed() {
        when(reconciliation.reconcile()).thenReturn(Mono.just(new ReconciliationReport(3, 1, 1)));

        StepVerifier.create(loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectNext(RETRY_INTERVAL)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void passOnce_shouldRetryAfterTheRetryInterval_whenTheServiceThrows() {
        when(reconciliation.reconcile()).thenThrow(new IllegalStateException("boom"));

        StepVerifier.create(loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectNext(RETRY_INTERVAL)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void passOnce_shouldRetryAfterTheRetryInterval_whenThePassHangs() {
        when(reconciliation.reconcile()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectSubscription()
                .expectNoEvent(INTERVAL.minusSeconds(1))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(RETRY_INTERVAL)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void passOnce_shouldWaitTheInterval_whenASlowPassEndsWithinTheInterval() {
        when(reconciliation.reconcile()).thenAnswer(invocation -> Mono.delay(INTERVAL.minusMinutes(1))
                .thenReturn(new ReconciliationReport(1, 1, 0)));

        StepVerifier.withVirtualTime(() -> loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectSubscription()
                .thenAwait(INTERVAL.minusMinutes(1))
                .expectNext(INTERVAL)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void passOnce_shouldRetryAfterTheRetryInterval_whenThePassCompletesEmpty() {
        when(reconciliation.reconcile()).thenReturn(Mono.empty());

        StepVerifier.create(loop(SHORT, INTERVAL, RETRY_INTERVAL).passOnce())
                .expectNext(RETRY_INTERVAL)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void start_shouldRunTheFirstPassAfterTheInitialDelay_thenWaitTheIntervalAfterACleanPass() {
        when(reconciliation.reconcile()).thenReturn(Mono.just(ReconciliationReport.NONE));
        DeletedUsersReconciliationLoop loop = loop(SHORT, LONG, SHORT);

        loop.start();
        try {
            verify(reconciliation, timeout(2000)).reconcile();
            verify(reconciliation, after(300).times(1)).reconcile();
        } finally {
            loop.stop();
        }
    }

    @Test
    void start_shouldPassAgainAfterTheRetryInterval_whenAPassFails() {
        when(reconciliation.reconcile())
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.USERS_UNAVAILABLE)))
                .thenReturn(Mono.just(new ReconciliationReport(1, 0, 1)))
                .thenReturn(Mono.just(ReconciliationReport.NONE));
        DeletedUsersReconciliationLoop loop = loop(SHORT, LONG, SHORT);

        loop.start();
        try {
            verify(reconciliation, timeout(2000).times(3)).reconcile();
            verify(reconciliation, after(300).times(3)).reconcile();
        } finally {
            loop.stop();
        }
    }

    @Test
    void stop_shouldCancelThePendingPass() {
        DeletedUsersReconciliationLoop loop = loop(Duration.ofMillis(200), LONG, LONG);

        loop.start();
        loop.stop();

        verify(reconciliation, after(500).never()).reconcile();
    }

    @Test
    void isRunning_shouldReflectStartAndStop() {
        DeletedUsersReconciliationLoop loop = loop(LONG, LONG, LONG);

        assertThat(loop.isRunning()).isFalse();
        loop.start();
        assertThat(loop.isRunning()).isTrue();
        loop.stop();
        assertThat(loop.isRunning()).isFalse();
    }

    @Test
    void stop_shouldBeHarmless_whenNeverStarted() {
        DeletedUsersReconciliationLoop loop = loop(LONG, LONG, LONG);

        loop.stop();

        assertThat(loop.isRunning()).isFalse();
        verify(reconciliation, never()).reconcile();
    }

    @Test
    void loop_shouldBeCreatedAndStarted_whenTheKillSwitchIsUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DeletedUsersReconciliationLoop.class);
            assertThat(context.getBean(DeletedUsersReconciliationLoop.class).isRunning()).isTrue();
        });
    }

    @Test
    void loop_shouldBeCreated_whenTheKillSwitchIsTrue() {
        contextRunner.withPropertyValues(KILL_SWITCH + "=true")
                .run(context -> assertThat(context).hasSingleBean(DeletedUsersReconciliationLoop.class));
    }

    @Test
    void loop_shouldNotBeCreated_whenTheKillSwitchIsFalse() {
        contextRunner.withPropertyValues(KILL_SWITCH + "=false")
                .run(context -> assertThat(context).doesNotHaveBean(DeletedUsersReconciliationLoop.class));
    }
}
