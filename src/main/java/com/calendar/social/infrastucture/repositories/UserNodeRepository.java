package com.calendar.social.infrastucture.repositories;

import com.calendar.social.infrastucture.models.entities.UserNodeEntity;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserNodeRepository extends ReactiveNeo4jRepository<Long, UserNodeEntity> {

}
