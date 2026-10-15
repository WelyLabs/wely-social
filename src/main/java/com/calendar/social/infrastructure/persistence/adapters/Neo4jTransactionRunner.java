package com.calendar.social.infrastructure.persistence.adapters;

import com.calendar.social.domain.ports.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** One Neo4j transaction around the work, which the repositories and {@code ReactiveNeo4jClient} join. */
@Component
public class Neo4jTransactionRunner implements TransactionRunner {

    private final TransactionalOperator transactionalOperator;

    public Neo4jTransactionRunner(TransactionalOperator transactionalOperator) {
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return transactionalOperator.transactional(work);
    }
}
