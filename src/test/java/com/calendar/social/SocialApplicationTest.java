package com.calendar.social;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers {@code main} only: without a broker, a context test would hang on the Kafka binder. */
class SocialApplicationTest {

    @Test
    void applicationClassShouldExposeOnlyAnEntryPoint() {
        // Synthetic methods are filtered out: JaCoCo instrumentation adds them.
        assertThat(SocialApplication.class.getDeclaredMethods())
                .filteredOn(method -> !method.isSynthetic())
                .extracting(Method::getName)
                .containsExactly("main");
    }
}
