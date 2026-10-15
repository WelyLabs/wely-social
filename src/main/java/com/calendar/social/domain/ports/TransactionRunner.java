package com.calendar.social.domain.ports;

import reactor.core.publisher.Mono;

/** Runs work atomically: every port call made while {@code work} runs commits together or not at all. */
public interface TransactionRunner {

    <T> Mono<T> inTransaction(Mono<T> work);
}
