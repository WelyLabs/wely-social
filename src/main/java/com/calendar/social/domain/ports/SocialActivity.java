package com.calendar.social.domain.ports;

/**
 * Product facts the social domain establishes, reported as they happen: how the graph and the
 * communities are used, not how the service behaves.
 *
 * <p>Each method is called once the fact is stored for good, never for an attempt that failed.
 * No identifiers are passed: the facts are counted, not attributed.
 */
public interface SocialActivity {

    /** A friend request was stored. */
    void friendRequestSent();

    /** A pending request was accepted: the two are now friends. */
    void friendRequestAccepted();

    /** A pending request was rejected by its receiver. */
    void friendRequestDeclined();

    /** A community was created. */
    void communityCreated();
}
