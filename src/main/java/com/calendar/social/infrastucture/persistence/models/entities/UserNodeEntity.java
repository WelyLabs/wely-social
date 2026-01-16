package com.calendar.social.infrastucture.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Node("User")
@Getter
@Setter
@AllArgsConstructor
public class UserNodeEntity {

    @Id
    private String id;

    private String userId;

    private String userName;

    private Integer hashtag;

    private String profilePicUrl;

    @Relationship(type = "FRIENDSHIP", direction = Relationship.Direction.OUTGOING)
    private List<RelationshipEntity> friendships;
}
