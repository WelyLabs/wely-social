package com.calendar.social.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business outcomes this service refuses on, each with a stable code.
 *
 * <p>{@code code} is the contract a client branches on. {@code title} and {@code detail}
 * are English, for whoever reads the response or the logs: the user-facing wording belongs
 * to the frontend, which knows the reader's language.
 */
@Getter
@AllArgsConstructor
public enum BusinessErrorCode {

    SEND_FRIEND_REQUEST_FAILURE(
            "SCL-BUS-001",
            "Friend request refused",
            "No such user, or a relationship already exists between the two.",
            HttpStatus.BAD_REQUEST),

    ACCEPT_FRIEND_REQUEST_FAILURE(
            "SCL-BUS-002",
            "Nothing to accept",
            "No pending request from this user.",
            HttpStatus.BAD_REQUEST),

    REJECT_FRIEND_REQUEST_FAILURE(
            "SCL-BUS-003",
            "Nothing to reject",
            "No pending request from this user.",
            HttpStatus.BAD_REQUEST),

    DELETE_FRIENDSHIP_FAILURE(
            "SCL-BUS-004",
            "No friendship to remove",
            "These two users are not friends.",
            HttpStatus.BAD_REQUEST),

    USER_DOES_NOT_EXIST(
            "SCL-BUS-005",
            "User not found",
            "No user matches the given handle.",
            HttpStatus.NOT_FOUND),

    INVALID_USER_TAG(
            "SCL-BUS-006",
            "Malformed handle",
            "A handle looks like Name#1234: up to thirty characters, then four digits.",
            HttpStatus.BAD_REQUEST);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
