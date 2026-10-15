package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityDraftTest {

    @Test
    void shouldTrimTheTextAndNormalizeTheInterests() {
        CommunityDraft draft = new CommunityDraft("  Club  ", "  Voitures  ",
                Arrays.asList(" Auto ", "auto", null, "  ", "Rando"), CommunityVisibility.PUBLIC);

        assertThat(draft.name()).isEqualTo("Club");
        assertThat(draft.description()).isEqualTo("Voitures");
        assertThat(draft.interests()).containsExactly("auto", "rando");
    }

    @Test
    void shouldReadABlankDescriptionAsNone() {
        assertThat(new CommunityDraft("Club", "   ", null, CommunityVisibility.PRIVATE).description()).isNull();
    }

    @Test
    void shouldReadNoInterestsAsAnEmptyList() {
        assertThat(new CommunityDraft("Club", null, null, CommunityVisibility.PRIVATE).interests()).isEmpty();
    }

    @Test
    void shouldKeepANullName() {
        assertThat(new CommunityDraft(null, null, List.of(), CommunityVisibility.PUBLIC).name()).isNull();
    }

    @Test
    void isValid_shouldAcceptADraftWithinTheLimits() {
        assertThat(new CommunityDraft("abc", "d".repeat(500), List.of("a", "b", "c", "d", "e"), CommunityVisibility.PUBLIC)
                .isValid()).isTrue();
        assertThat(new CommunityDraft("n".repeat(60), null, List.of("i".repeat(30)), CommunityVisibility.PRIVATE)
                .isValid()).isTrue();
    }

    @Test
    void isValid_shouldRefuseEachBrokenRule() {
        assertThat(new CommunityDraft(null, null, List.of(), CommunityVisibility.PUBLIC).isValid()).isFalse();
        assertThat(new CommunityDraft("ab", null, List.of(), CommunityVisibility.PUBLIC).isValid()).isFalse();
        assertThat(new CommunityDraft("n".repeat(61), null, List.of(), CommunityVisibility.PUBLIC).isValid()).isFalse();
        assertThat(new CommunityDraft("Club", "d".repeat(501), List.of(), CommunityVisibility.PUBLIC).isValid()).isFalse();
        assertThat(new CommunityDraft("Club", null, List.of("a", "b", "c", "d", "e", "f"), CommunityVisibility.PUBLIC)
                .isValid()).isFalse();
        assertThat(new CommunityDraft("Club", null, List.of("i".repeat(31)), CommunityVisibility.PUBLIC).isValid()).isFalse();
        assertThat(new CommunityDraft("Club", null, List.of(), null).isValid()).isFalse();
    }
}
