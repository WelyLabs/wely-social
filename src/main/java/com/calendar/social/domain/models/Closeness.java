package com.calendar.social.domain.models;

/**
 * How close two friends are, from the loosest to the closest. Each step up is agreed by both;
 * either can step down alone. What each level reveals of a calendar is wely-events' business.
 */
public enum Closeness {
    FRIEND,
    CLOSE,
    INTIMATE;

    public boolean isBelow(Closeness other) {
        return compareTo(other) < 0;
    }
}
