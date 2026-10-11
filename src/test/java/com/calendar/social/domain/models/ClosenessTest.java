package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClosenessTest {

    @Test
    void isBelow_shouldOrderFromFriendToIntimate() {
        assertThat(Closeness.FRIEND.isBelow(Closeness.CLOSE)).isTrue();
        assertThat(Closeness.CLOSE.isBelow(Closeness.INTIMATE)).isTrue();
        assertThat(Closeness.INTIMATE.isBelow(Closeness.FRIEND)).isFalse();
        assertThat(Closeness.CLOSE.isBelow(Closeness.CLOSE)).isFalse();
    }
}
