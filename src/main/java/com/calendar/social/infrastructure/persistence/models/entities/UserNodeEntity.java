package com.calendar.social.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * A user in the social graph. {@code userId} is the business identity — every Cypher
 * query in this service matches on it.
 *
 * <p>Relationships are deliberately not mapped as a field. They are driven entirely by
 * the explicit queries in {@code UserNodeRepository}, so mapping them here would only
 * add eager loading nobody asked for. An earlier version declared such a field on
 * relationship type {@code FRIENDSHIP}, while every query uses {@code RELATIONSHIP},
 * so it was always empty.
 */
@Node("User")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UserNodeEntity {

    @Id
    private String userId;

    private String userName;

    private Integer hashtag;

    private String profilePicUrl;
}
