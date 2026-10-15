package com.calendar.social.domain.models;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a community is created or edited with, normalized: trimmed text, interests lower-cased and
 * deduplicated. {@link #isValid()} says whether it may be stored.
 */
public record CommunityDraft(String name, String description, List<String> interests, CommunityVisibility visibility) {

    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 60;
    public static final int DESCRIPTION_MAX = 500;
    public static final int INTERESTS_MAX = 5;
    public static final int INTEREST_MAX = 30;

    public CommunityDraft {
        name = name == null ? null : name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        interests = interests == null ? List.of() : List.copyOf(new LinkedHashSet<>(interests.stream()
                .filter(Objects::nonNull)
                .map(interest -> interest.strip().toLowerCase(Locale.ROOT))
                .filter(interest -> !interest.isEmpty())
                .toList()));
    }

    public boolean isValid() {
        return name != null && name.length() >= NAME_MIN && name.length() <= NAME_MAX
                && (description == null || description.length() <= DESCRIPTION_MAX)
                && interests.size() <= INTERESTS_MAX
                && interests.stream().allMatch(interest -> interest.length() <= INTEREST_MAX)
                && visibility != null;
    }
}
