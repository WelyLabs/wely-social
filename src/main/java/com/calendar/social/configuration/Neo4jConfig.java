package com.calendar.social.configuration;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.transaction.ReactiveNeo4jTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;

@Configuration
public class Neo4jConfig {

    @Bean
    public ReactiveTransactionManager reactiveTransactionManager(Driver driver) {
        return new ReactiveNeo4jTransactionManager(driver);
    }
}
