package com.calendar.social.domain.ports;

import java.util.ArrayList;
import java.util.List;

/** A {@link SocialActivity} for domain tests: remembers the facts reported, in order, without Micrometer. */
public class RecordingSocialActivity implements SocialActivity {

    public static final String FRIEND_REQUEST_SENT = "friendRequestSent";
    public static final String FRIEND_REQUEST_ACCEPTED = "friendRequestAccepted";
    public static final String FRIEND_REQUEST_DECLINED = "friendRequestDeclined";
    public static final String COMMUNITY_CREATED = "communityCreated";

    private final List<String> facts = new ArrayList<>();

    public List<String> facts() {
        return List.copyOf(facts);
    }

    @Override
    public void friendRequestSent() {
        facts.add(FRIEND_REQUEST_SENT);
    }

    @Override
    public void friendRequestAccepted() {
        facts.add(FRIEND_REQUEST_ACCEPTED);
    }

    @Override
    public void friendRequestDeclined() {
        facts.add(FRIEND_REQUEST_DECLINED);
    }

    @Override
    public void communityCreated() {
        facts.add(COMMUNITY_CREATED);
    }
}
