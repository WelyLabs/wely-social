package com.calendar.social.domain.models;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public handle of a user, {@code Name#1234} — the only identifier that travels
 * through the API, so internal ids stay out of the client contract.
 *
 * <p>The format lives here rather than being split between a Bean Validation
 * pattern and a {@code split("#")} elsewhere: those two drifted apart, and a name
 * containing {@code #} passed validation then broke the parse with a 500.
 */
public record UserTag(String userName, int hashtag) {

    /** Name holds anything but {@code #}, so the separator is unambiguous. */
    public static final String PATTERN = "^[^#]{3,30}#\\d{4}$";

    private static final Pattern COMPILED = Pattern.compile("^([^#]{3,30})#(\\d{4})$");

    public static UserTag parse(String raw) {
        if (raw == null) {
            throw new BusinessException(BusinessErrorCode.INVALID_USER_TAG);
        }

        Matcher matcher = COMPILED.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_USER_TAG);
        }

        return new UserTag(matcher.group(1), Integer.parseInt(matcher.group(2)));
    }

    @Override
    public String toString() {
        return userName + "#" + hashtag;
    }
}
