package com.calendar.social.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerSocialActivityTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerSocialActivity activity = new MicrometerSocialActivity(meterRegistry);

    private double friendRequests(String outcome) {
        return meterRegistry.get("wely.social.friend.requests").tag("outcome", outcome).counter().count();
    }

    @Test
    void constructor_shouldRegisterEverySeriesAtZero() {
        assertThat(friendRequests("sent")).isZero();
        assertThat(friendRequests("accepted")).isZero();
        assertThat(friendRequests("declined")).isZero();
        assertThat(meterRegistry.get("wely.social.community.creations").counter().count()).isZero();
    }

    @Test
    void friendRequestSent_shouldCountUnderSent() {
        activity.friendRequestSent();
        activity.friendRequestSent();

        assertThat(friendRequests("sent")).isEqualTo(2.0);
        assertThat(friendRequests("accepted")).isZero();
    }

    @Test
    void friendRequestAccepted_shouldCountUnderAccepted() {
        activity.friendRequestAccepted();

        assertThat(friendRequests("accepted")).isEqualTo(1.0);
    }

    @Test
    void friendRequestDeclined_shouldCountUnderDeclined() {
        activity.friendRequestDeclined();

        assertThat(friendRequests("declined")).isEqualTo(1.0);
    }

    @Test
    void communityCreated_shouldCountTheCommunity() {
        activity.communityCreated();

        assertThat(meterRegistry.get("wely.social.community.creations").counter().count()).isEqualTo(1.0);
    }
}
