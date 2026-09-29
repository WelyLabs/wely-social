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
    @DisplayName("un nom contenant # ne passe plus : c'était le contournement de l'ancienne regex")
    void parse_shouldRejectExtraSeparator() {
        // L'ancienne regex ^.+#\d{4,6}$ acceptait cette valeur — le .+ étant gourmand,
        // il absorbait le premier #. Le split("#") renvoyait alors ["a", "b", "1234"] et
        // Integer.parseInt("b") levait une NumberFormatException remontée en 500.
        assertThatThrownBy(() -> UserTag.parse("a#b#1234"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BusinessErrorCode.INVALID_USER_TAG);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",           // vide
            "theo",       // pas de séparateur
            "theo#",      // pas de hashtag
            "theo#xx",    // hashtag non numérique
            "theo#123",   // trop court
            "theo#12345", // trop long
            "#4271",      // pas de nom
            "ab#4271",    // nom trop court
            "a#b#1234",   // séparateur en trop
    })
    void parse_shouldRejectMalformedInput(String raw) {
        assertThatThrownBy(() -> UserTag.parse(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BusinessErrorCode.INVALID_USER_TAG);
    }

    @Test
    @DisplayName("la regex exposée au DTO accepte exactement ce que parse accepte")
    void pattern_shouldAgreeWithParse() {
        Pattern dtoPattern = Pattern.compile(UserTag.PATTERN);

        for (String valid : new String[] {"theo#4271", "jean-luc#0001", "Ana Maria#9999"}) {
            assertThat(dtoPattern.matcher(valid).matches())
                    .as("la regex du DTO devrait accepter %s", valid)
                    .isTrue();
            assertThat(UserTag.parse(valid)).isNotNull();
        }

        for (String invalid : new String[] {"theo", "theo#xx", "a#b#1234", "theo#12345"}) {
            assertThat(dtoPattern.matcher(invalid).matches())
                    .as("la regex du DTO devrait rejeter %s", invalid)
                    .isFalse();
        }
    }

    @Test
    void toString_shouldRebuildTheHandle() {
        assertThat(new UserTag("theo", 4271)).hasToString("theo#4271");
    }
}
