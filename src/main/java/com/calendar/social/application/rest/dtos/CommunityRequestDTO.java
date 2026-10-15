package com.calendar.social.application.rest.dtos;

import com.calendar.social.domain.models.CommunityVisibility;

import java.util.List;

/** Creates or edits a community; the rules are the domain's, see {@code CommunityDraft}. */
public record CommunityRequestDTO(
        String name,
        String description,
        List<String> interests,
        CommunityVisibility visibility
) {}
