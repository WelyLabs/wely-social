package com.calendar.social.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.data.neo4j.core.transaction.ReactiveNeo4jTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class Neo4jConfigTest {

    private final Neo4jConfig config = new Neo4jConfig();

    @Test
    @DisplayName("the transaction manager is the reactive variant, not the blocking one")
    void reactiveTransactionManager_shouldBeTheReactiveNeo4jManager() {
        Driver driver = mock(Driver.class);

        ReactiveTransactionManager manager = config.reactiveTransactionManager(driver);

        // The imperative manager would compile just as well and block the event loop,
        // which is precisely the trap this bean exists to avoid.
        assertThat(manager).isInstanceOf(ReactiveNeo4jTransactionManager.class);
    }

    @Test
    void reactiveTransactionManager_shouldNotTouchTheDriverAtConstruction() {
        Driver driver = mock(Driver.class);

        config.reactiveTransactionManager(driver);

        // Nothing connects at assembly: the driver is only used when queried.
        verifyNoInteractions(driver);
    }
}
