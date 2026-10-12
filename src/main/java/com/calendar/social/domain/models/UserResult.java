package com.calendar.social.domain.models;

public sealed interface UserResult permits UserNodeDTO, UserSocialDTO, FriendRequest {}
