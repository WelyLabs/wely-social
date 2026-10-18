package com.calendar.social.infrastructure.messaging.relay;

import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import com.calendar.social.infrastructure.messaging.adapters.UnpublishableRecordException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PublicationErrorClassifierTest {

    /** Its getter throws, which Jackson reports as one of its own exceptions. */
    static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    /** The shape StreamBridge throws a synchronous send's failure in. */
    private static Throwable asTheBinderThrowsIt(Throwable producerError) {
        return new MessageHandlingException(MessageBuilder.withPayload("x").build(), "send failed",
                new RuntimeException("Send failed", new ExecutionException(producerError)));
    }

    @Test
    void isRetriable_shouldBeFalse_forARecordWhoseMessagesCannotBeBuilt() {
        assertThat(PublicationErrorClassifier.isRetriable(
                new UnpublishableRecordException(42L, new IllegalArgumentException("unmappable")))).isFalse();
    }

    @Test
    void isRetriable_shouldBeFalse_forSerializationFailures() {
        assertThat(PublicationErrorClassifier.isRetriable(new SerializationException("bad key"))).isFalse();
        Throwable jacksonError = catchThrowable(() -> JsonMapper.builder().build().writeValueAsBytes(new Exploding()));
        assertThat(jacksonError).isInstanceOf(JacksonException.class);
        assertThat(PublicationErrorClassifier.isRetriable(jacksonError)).isFalse();
        assertThat(PublicationErrorClassifier.isRetriable(new MessageConversionException("no converter"))).isFalse();
    }

    @Test
    @DisplayName("too large and invalid topic are permanent, however deep the binder wraps them")
    void isRetriable_shouldBeFalse_forErrorsTheBrokerWillRepeat_evenWrapped() {
        assertThat(PublicationErrorClassifier.isRetriable(asTheBinderThrowsIt(new RecordTooLargeException("big"))))
                .isFalse();
        assertThat(PublicationErrorClassifier.isRetriable(asTheBinderThrowsIt(new RecordBatchTooLargeException("big"))))
                .isFalse();
        assertThat(PublicationErrorClassifier.isRetriable(asTheBinderThrowsIt(new InvalidTopicException("bad name"))))
                .isFalse();
    }

    @Test
    void isRetriable_shouldBeTrue_forAnUnreachableBroker_orATimeout() {
        assertThat(PublicationErrorClassifier.isRetriable(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)))
                .isTrue();
        assertThat(PublicationErrorClassifier.isRetriable(asTheBinderThrowsIt(new TimeoutException("no ack")))).isTrue();
        assertThat(PublicationErrorClassifier.isRetriable(asTheBinderThrowsIt(new UnknownTopicOrPartitionException("x"))))
                .isTrue();
    }

    @Test
    void isRetriable_shouldBeTrue_forAnUnknownError() {
        assertThat(PublicationErrorClassifier.isRetriable(new IllegalStateException("graph down"))).isTrue();
    }

    @Test
    void isRetriable_shouldStop_onACauseLoop() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(PublicationErrorClassifier.isRetriable(first)).isTrue();
    }
}
