package com.calendar.social.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.ZonedDateTime;

/**
 * A FRIENDSHIP_CHANGED record waiting for, or past, its publication. Pending ones also carry the
 * {@code Unpublished} label, which the relay's query scans instead of every record kept.
 */
@Node("FriendshipOutboxEvent")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FriendshipOutboxEventEntity {

    /** Taken from the FriendshipClock: the publication order, and the record's version for consumers. */
    @Id
    private Long version;

    private String type;
    private String fromUserId;
    /** Null when the actor's node is gone, as after an account erasure. */
    private String fromUserName;
    private String toUserId;
    /** The two ids sorted and joined with ':', the Kafka record key. */
    private String pairKey;
    /** The pair's closeness after the change; null when they are not friends. */
    private String closeness;
    /** The pair's closeness before the change; null when they were not friends, or for a SNAPSHOT. */
    private String previousCloseness;
    /** When they became friends; null when they are not. */
    private ZonedDateTime since;
    private ZonedDateTime occurredAt;
    private ZonedDateTime publishedAt;
    private Long attempts;
    private String lastError;
}
