package com.calendar.social.domain.models;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTagTest {

    @Test
    void parse_shouldSplitNameAndHashtag() {
        UserTag tag = UserTag.parse("theo#4271");

        assertThat(tag.userName()).isEqualTo("theo");
        assertThat(tag.hashtag()).isEqualTo(4271);
    }

    @Test
    void parse_shouldTrimSurroundingWhitespace() {
        assertThat(UserTag.parse("  theo#4271  ")).isEqualTo(new UserTag("theo", 4271));
    }

    @Test
    @DisplayName("a name containing # no longer passes: that was the old pattern's bypass")
    void parse_shouldRejectExtraSeparator() {
        // The old pattern ^.+#\d{4,6}$ accepted this value: .+ is greedy and swallowed
        // the first #, so split("#") returned ["a", "b", "1234"] and
        // Integer.parseInt("b") threw a NumberFormatException, surfacing as a 500.
        assertThatThrownBy(() -> UserTag.parse("a#b#1234"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BusinessErrorCode.INVALID_USER_TAG);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",           // empty
            "theo",       // no separator
            "theo#",      // no hashtag
            "theo#xx",    // non-numeric hashtag
            "theo#123",   // too short
            "theo#12345", // too long
            "#4271",      // no name
            "ab#4271",    // name too short
            "a#b#1234",   // extra separator
    })
    void parse_shouldRejectMalformedInput(String raw) {
        assertThatThrownBy(() -> UserTag.parse(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BusinessErrorCode.INVALID_USER_TAG);
    }

    @Test
    @DisplayName("the pattern exposed to the DTO accepts exactly what parse accepts")
    void pattern_shouldAgreeWithParse() {
        Pattern dtoPattern = Pattern.compile(UserTag.PATTERN);

        for (String valid : new String[] {"theo#4271", "jean-luc#0001", "Ana Maria#9999"}) {
            assertThat(dtoPattern.matcher(valid).matches())
                    .as("the DTO pattern should accept %s", valid)
                    .isTrue();
            assertThat(UserTag.parse(valid)).isNotNull();
        }

        for (String invalid : new String[] {"theo", "theo#xx", "a#b#1234", "theo#12345"}) {
            assertThat(dtoPattern.matcher(invalid).matches())
                    .as("the DTO pattern should reject %s", invalid)
                    .isFalse();
        }
    }

    @Test
    void toString_shouldRebuildTheHandle() {
        assertThat(new UserTag("theo", 4271)).hasToString("theo#4271");
    }
}
