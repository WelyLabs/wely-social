package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.exception.GlobalErrorHandler;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalErrorCounter;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.persistence.adapters.Neo4jFriendshipOutboxAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FriendshipOutboxBacklogGaugeTest {

    private final Neo4jFriendshipOutboxAdapter outbox = mock(Neo4jFriendshipOutboxAdapter.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final FriendshipOutboxBacklogGauge gauge = new FriendshipOutboxBacklogGauge(outbox,
            new TechnicalErrorCounter(meterRegistry), meterRegistry, Duration.ofMillis(20));

    private double exposed() {
        return meterRegistry.get("wely.outbox.pending").tag("outbox", "friendship").gauge().value();
    }

    @Test
    void constructor_shouldExposeZero_beforeTheFirstRefresh() {
        assertThat(exposed()).isZero();
    }

    @Test
    void refresh_shouldExposeTheCountOfPendingRecords() {
        when(outbox.countPending()).thenReturn(Mono.just(12L));

        StepVerifier.create(gauge.refresh()).expectNext(12L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(exposed()).isEqualTo(12.0);
    }

    @Test
    void refresh_shouldKeepTheLastValue_andCountTheFailure_whenTheCountFails() {
        when(outbox.countPending())
                .thenReturn(Mono.just(5L))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.DATABASE_ERROR)));
        StepVerifier.create(gauge.refresh()).expectNext(5L).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(gauge.refresh()).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(exposed()).isEqualTo(5.0);
        assertThat(meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, "SCL-TEC-001")
                .tag(GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void start_shouldRefreshRepeatedly_andSurviveAFailedRefresh() {
        when(outbox.countPending())
                .thenReturn(Mono.error(new IllegalStateException("graph down")))
                .thenReturn(Mono.just(3L));

        gauge.start();
        try {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(exposed()).isEqualTo(3.0));
        } finally {
            gauge.stop();
        }
    }

    @Test
    void isRunning_shouldReflectStartAndStop() {
        when(outbox.countPending()).thenReturn(Mono.just(0L));

        gauge.start();
        assertThat(gauge.isRunning()).isTrue();
        gauge.stop();
        assertThat(gauge.isRunning()).isFalse();
    }
}
