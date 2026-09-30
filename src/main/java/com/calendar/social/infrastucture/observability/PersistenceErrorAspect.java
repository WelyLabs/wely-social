package com.calendar.social.infrastucture.observability;

import com.calendar.social.exception.BusinessException;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Logs and translates persistence failures for every adapter method, once.
 *
 * <h2>Why an aspect</h2>
 *
 * <p>Ten adapter methods repeated the same five lines: log the error, then map it to
 * {@code TechnicalException(DATABASE_ERROR)}. Identical intent, ten copies, and they had
 * already drifted — two of them carried log messages describing the wrong operation.
 * Logging is the textbook cross-cutting concern, and the translation here is uniform
 * enough to travel with it: every failure out of this layer is a database failure.
 *
 * <h2>The reactive catch</h2>
 *
 * <p>A plain {@code @AfterThrowing} advice would catch almost nothing. These methods do
 * not throw — they return a {@link Mono} or {@link Flux} immediately, and the failure
 * surfaces later, on another thread, as an error signal inside that publisher. So the
 * advice has to <em>wrap the returned publisher</em> rather than guard the call.
 *
 * <h2>What is deliberately not logged</h2>
 *
 * <p>Method arguments. They are tempting and they are a leak: adapters in sibling
 * services take message bodies and user payloads, and an aspect that logs {@code args}
 * puts them in the cluster logs the moment someone reuses this class. The method name
 * and the exception message are enough to locate a failure.
 */
@Slf4j
@Aspect
@Component
public class PersistenceErrorAspect {

    /** Every public method of every persistence adapter in this service. */
    @Pointcut("execution(public * com.calendar.social.infrastucture.persistence.adapters.*.*(..))")
    public void persistenceAdapterMethod() {
        // Pointcut declaration only.
    }

    @Around("persistenceAdapterMethod()")
    public Object translateFailures(ProceedingJoinPoint joinPoint) throws Throwable {
        String operation = joinPoint.getSignature().getName();
        Object result = joinPoint.proceed();

        if (result instanceof Mono<?> mono) {
            return mono.onErrorMap(error -> translate(operation, error));
        }
        if (result instanceof Flux<?> flux) {
            return flux.onErrorMap(error -> translate(operation, error));
        }

        return result;
    }

    /**
     * Domain failures pass through untouched: a {@link BusinessException} raised by a
     * {@code switchIfEmpty} is the adapter saying "no such relationship", not a database
     * problem, and an already-translated {@link TechnicalException} must not be wrapped
     * twice. Anything else is infrastructure.
     */
    private Throwable translate(String operation, Throwable error) {
        if (error instanceof BusinessException || error instanceof TechnicalException) {
            return error;
        }

        log.error("Neo4j failure in {}: {}", operation, error.getMessage(), error);
        return new TechnicalException(TechnicalErrorCode.DATABASE_ERROR);
    }
}
