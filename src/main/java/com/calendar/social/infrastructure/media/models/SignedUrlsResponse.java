package com.calendar.social.infrastructure.media.models;

import java.util.Map;

/** wely-media's signed URLs, by key. */
public record SignedUrlsResponse(Map<String, String> urls) {}
