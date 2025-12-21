package com.calendar.social.infrastucture.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("User")
@Getter
@Setter
@AllArgsConstructor
public class UserNodeEntity {

    @Id
    private Long id;

    private String userName;

    private String profilePicUrl;
}
