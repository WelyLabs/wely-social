package com.calendar.social.domain.models;

import java.util.List;

/** One page of wely-users' tombstones, oldest first; {@code next} is an opaque cursor, null on the last page. */
public record DeletedUsersPage(List<DeletedUser> items, String next) {

    public DeletedUsersPage {
        items = List.copyOf(items);
    }
}
