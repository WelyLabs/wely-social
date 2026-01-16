package com.calendar.social.infrastucture.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.time.LocalDateTime;

@RelationshipProperties
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class RelationshipEntity {
    @RelationshipId
    private String id;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime acceptedAt;

    private LocalDateTime rejectedAt;

    @TargetNode
    private UserNodeEntity targetUser;
}
