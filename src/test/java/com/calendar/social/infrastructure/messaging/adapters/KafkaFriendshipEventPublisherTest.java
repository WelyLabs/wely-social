package com.calendar.social.infrastructure.messaging.adapters;

import com.calendar.social.domain.models.FriendshipEvent;
import com.calendar.social.domain.models.FriendshipEventType;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.mappers.FriendshipChangedMapper;
import com.calendar.social.infrastructure.messaging.models.FriendshipChangedDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageDeliveryException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaFriendshipEventPublisherTest {

    private static final FriendshipEvent REQUESTED =
            new FriendshipEvent(FriendshipEventType.REQUESTED, "alice", "Alice", "bob");
    private static final FriendshipChangedDTO SENT = new FriendshipChangedDTO("REQUESTED", "alice", "Alice", "bob");

    @Mock
    private StreamBridge streamBridge;

    private KafkaFriendshipEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaFriendshipEventPublisher(streamBridge, Mappers.getMapper(FriendshipChangedMapper.class));
    }

    @Test
    void publish_shouldSendTheEventOnItsBinding() {
        when(streamBridge.send(KafkaFriendshipEventPublisher.BINDING, SENT)).thenReturn(true);

        StepVerifier.create(publisher.publish(REQUESTED)).verifyComplete();

        verify(streamBridge).send(KafkaFriendshipEventPublisher.BINDING, SENT);
        assertThat(KafkaFriendshipEventPublisher.BINDING).isEqualTo("friendship-out-0");
    }

    @Test
    void publish_shouldCompleteNormally_whenTheBinderRefusesTheRecord() {
        when(streamBridge.send(KafkaFriendshipEventPublisher.BINDING, SENT)).thenReturn(false);

        StepVerifier.create(publisher.publish(REQUESTED)).verifyComplete();
    }

    @Test
    void publish_shouldTranslateABinderFailure() {
        when(streamBridge.send(KafkaFriendshipEventPublisher.BINDING, SENT))
                .thenThrow(new MessageDeliveryException("broker down"));

        StepVerifier.create(publisher.publish(REQUESTED))
                .expectErrorMatches(e -> e instanceof TechnicalException t
                        && t.getErrorCode() == TechnicalErrorCode.BROKER_UNAVAILABLE)
                .verify();
    }
}
