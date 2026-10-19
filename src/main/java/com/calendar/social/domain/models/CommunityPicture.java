package com.calendar.social.domain.models;

import java.util.UUID;

/** Where a community's picture lives in wely-media: one folder per community, under {@code communities/}. */
public final class CommunityPicture {

    public static final String PREFIX = "communities/";

    private CommunityPicture() {
    }

    /** A fresh key, so a new picture never reuses the URL a browser may still have cached. */
    public static String newKey(String communityId) {
        return folder(communityId) + "picture-" + UUID.randomUUID() + ".jpg";
    }

    /** The community's folder: deleting it takes the current picture and any orphaned older one. */
    public static String folder(String communityId) {
        return PREFIX + communityId + "/";
    }
}
