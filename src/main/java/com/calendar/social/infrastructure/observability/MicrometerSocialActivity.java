package com.calendar.social.infrastructure.observability;

import com.calendar.social.domain.ports.SocialActivity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link SocialActivity} as Micrometer counters, the names Grafana's dashboards are written against.
 *
 * <p>Every series is registered up front, so a dashboard shows 0 rather than "no data" before the
 * first fact of a kind.
 */
@Component
public class MicrometerSocialActivity implements SocialActivity {

    public static final String FRIEND_REQUESTS = "wely.social.friend.requests";
    // Not "communities.created": the Prometheus client drops a "_created" suffix from counter names.
    public static final String COMMUNITY_CREATIONS = "wely.social.community.creations";
    public static final String OUTCOME_TAG = "outcome";
    public static final String SENT = "sent";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";

    private final Counter requestsSent;
    private final Counter requestsAccepted;
    private final Counter requestsDeclined;
    private final Counter communitiesCreated;

    public MicrometerSocialActivity(MeterRegistry meterRegistry) {
        this.requestsSent = friendRequests(meterRegistry, SENT);
        this.requestsAccepted = friendRequests(meterRegistry, ACCEPTED);
        this.requestsDeclined = friendRequests(meterRegistry, DECLINED);
        this.communitiesCreated = Counter.builder(COMMUNITY_CREATIONS)
                .description("Communities created")
                .register(meterRegistry);
    }

    private static Counter friendRequests(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(FRIEND_REQUESTS)
                .description("Friend requests, by what became of them")
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry);
    }

    @Override
    public void friendRequestSent() {
        requestsSent.increment();
    }

    @Override
    public void friendRequestAccepted() {
        requestsAccepted.increment();
    }

    @Override
    public void friendRequestDeclined() {
        requestsDeclined.increment();
    }

    @Override
    public void communityCreated() {
        communitiesCreated.increment();
    }
}
