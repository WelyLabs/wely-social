package com.calendar.social.infrastucture.models.entities;

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
    private Long id;

    private String userName;

    private String profilePicUrl;

    @Relationship(type = "FRIENDSHIP", direction = Relationship.Direction.OUTGOING)
    private List<FriendshipEntity> friendships;
}
