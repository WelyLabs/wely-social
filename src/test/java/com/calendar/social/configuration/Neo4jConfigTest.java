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
    @DisplayName("le gestionnaire de transactions est la variante réactive, pas la bloquante")
    void reactiveTransactionManager_shouldBeTheReactiveNeo4jManager() {
        Driver driver = mock(Driver.class);

        ReactiveTransactionManager manager = config.reactiveTransactionManager(driver);

        // Le manager impératif fonctionnerait à la compilation mais bloquerait
        // l'event loop : c'est précisément le piège que ce bean évite.
        assertThat(manager).isInstanceOf(ReactiveNeo4jTransactionManager.class);
    }

    @Test
    void reactiveTransactionManager_shouldNotTouchTheDriverAtConstruction() {
        Driver driver = mock(Driver.class);

        config.reactiveTransactionManager(driver);

        // Aucune connexion à l'assemblage : le driver n'est sollicité qu'à l'usage.
        verifyNoInteractions(driver);
    }
}
