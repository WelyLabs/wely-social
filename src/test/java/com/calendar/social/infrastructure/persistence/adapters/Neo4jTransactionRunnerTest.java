package com.calendar.social.infrastructure.persistence.adapters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Neo4jTransactionRunnerTest {

    @Mock
    private TransactionalOperator transactionalOperator;

    @Test
    void inTransaction_shouldDelegateToTheTransactionalOperator() {
        Mono<String> work = Mono.just("done");
        when(transactionalOperator.transactional(work)).thenReturn(Mono.just("committed"));

        StepVerifier.create(new Neo4jTransactionRunner(transactionalOperator).inTransaction(work))
                .expectNext("committed")
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(transactionalOperator).transactional(work);
    }

    @Test
    void inTransaction_shouldPropagateTheError_whenTheWorkFails() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StepVerifier.create(new Neo4jTransactionRunner(transactionalOperator)
                        .inTransaction(Mono.error(new IllegalStateException("rolled back"))))
                .expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));
    }
}
