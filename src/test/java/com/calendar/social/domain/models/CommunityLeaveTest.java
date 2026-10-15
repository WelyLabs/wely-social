package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityLeaveTest {

    @Test
    void left_shouldNeitherDeleteNorHandOver() {
        assertThat(CommunityLeave.left()).isEqualTo(new CommunityLeave(false, null));
    }

    @Test
    void handedOverTo_shouldNameTheNewAdmin() {
        assertThat(CommunityLeave.handedOverTo("u")).isEqualTo(new CommunityLeave(false, "u"));
    }

    @Test
    void deleted_shouldSaySo() {
        assertThat(CommunityLeave.deleted()).isEqualTo(new CommunityLeave(true, null));
    }
}
