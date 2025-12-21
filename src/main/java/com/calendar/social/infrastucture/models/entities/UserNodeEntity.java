package com.calendar.social.infrastucture.models.entities;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("User")
public class UserNodeEntity {

    @Id
    private Long id;

    private String userName;

    private String profilePicUrl;
}
