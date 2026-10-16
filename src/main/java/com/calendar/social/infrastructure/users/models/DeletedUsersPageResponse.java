package com.calendar.social.infrastructure.users.models;

import java.util.List;

/** wely-users' internal listing page: {@code {"items": [...], "next": "<cursor>" | null}}. */
public record DeletedUsersPageResponse(List<DeletedUserResponse> items, String next) {
}
