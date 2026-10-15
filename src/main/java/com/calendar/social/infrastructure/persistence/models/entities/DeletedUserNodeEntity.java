package com.calendar.social.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.ZonedDateTime;

/** Permanent marker of a deleted account: only the pseudonymous businessId and two timestamps. */
@Node("DeletedUser")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class DeletedUserNodeEntity {

    @Id
    private String userId;

    private ZonedDateTime deletedAt;

    /** When the erasure completed; null while it has not. */
    private ZonedDateTime erasedAt;
}
