package com.calendar.social.domain.models;

/**
 * Someone a close friend is friends with, as that friend's circle is shown to them. Carries no
 * closeness: how close two other people are is theirs, not the viewer's to read.
 */
public record FriendOfFriend(String userId, String userName, Integer hashtag, String profilePicUrl) {
}
