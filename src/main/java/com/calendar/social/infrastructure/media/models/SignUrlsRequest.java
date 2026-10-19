package com.calendar.social.infrastructure.media.models;

import java.util.Collection;

/** The body of wely-media's POST /internal/media/urls. */
public record SignUrlsRequest(Collection<String> keys, long ttlSeconds) {}
