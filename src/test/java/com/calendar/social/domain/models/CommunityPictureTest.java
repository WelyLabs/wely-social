package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityPictureTest {

    @Test
    void newKey_shouldLiveInTheCommunitysFolder() {
        assertThat(CommunityPicture.newKey("c1")).matches("communities/c1/picture-[0-9a-f-]{36}\\.jpg");
    }

    @Test
    void newKey_shouldNeverRepeat_soABrowserCacheNeverShowsTheOldPicture() {
        assertThat(CommunityPicture.newKey("c1")).isNotEqualTo(CommunityPicture.newKey("c1"));
    }

    @Test
    void folder_shouldEndWithASlash_soItCoversNoOtherCommunity() {
        assertThat(CommunityPicture.folder("c1")).isEqualTo("communities/c1/");
    }
}
