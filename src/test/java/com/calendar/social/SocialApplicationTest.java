package com.calendar.social;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SocialApplication} only declares {@code main}, so there is nothing else to
 * cover here.
 *
 * <p>No {@code @SpringBootTest}: this service binds a Kafka <em>consumer</em>, and the
 * binder polls the broker while the context starts, retrying indefinitely when none is
 * running — the context never finishes booting, with or without a declared binding.
 * The producer side in wely-users has no such problem and does get a context test.
 * Verifying this service's wiring for real needs a broker, so it belongs in the
 * Testcontainers suite rather than here.
 *
 * <p>The configuration classes are covered individually instead — see
 * {@code KafkaConsumerConfigTest} and the tests under {@code configuration/}.
 */
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
