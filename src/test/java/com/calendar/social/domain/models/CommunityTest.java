package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityTest {

    private static Community withRole(CommunityRole role) {
        return new Community("c", "Club", null, null, CommunityVisibility.PUBLIC, Instant.EPOCH, 1, role, null, 0);
    }

    @Test
    void shouldReadMissingListsAsEmpty() {
        Community community = withRole(null);

        assertThat(community.interests()).isEmpty();
        assertThat(community.friendMembers()).isEmpty();
    }

    @Test
    void shouldCopyItsLists() {
        List<String> interests = new ArrayList<>(List.of("auto"));
        Community community = new Community("c", "Club", null, interests, CommunityVisibility.PUBLIC, Instant.EPOCH, 1,
                null, List.of(), 0);
        interests.add("rando");

        assertThat(community.interests()).containsExactly("auto");
        assertThatThrownBy(() -> community.interests().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isMember_shouldFollowTheRole() {
        assertThat(withRole(null).isMember()).isFalse();
        assertThat(withRole(CommunityRole.MEMBER).isMember()).isTrue();
        assertThat(withRole(CommunityRole.ADMIN).isMember()).isTrue();
    }

    @Test
    void isAdmin_shouldBeTrueForAdminsOnly() {
        assertThat(withRole(null).isAdmin()).isFalse();
        assertThat(withRole(CommunityRole.MEMBER).isAdmin()).isFalse();
        assertThat(withRole(CommunityRole.ADMIN).isAdmin()).isTrue();
    }
}
