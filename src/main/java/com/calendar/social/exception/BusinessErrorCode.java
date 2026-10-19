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
            "No user matches the given handle or id.",
            HttpStatus.NOT_FOUND),

    INVALID_USER_TAG(
            "SCL-BUS-006",
            "Malformed handle",
            "A handle looks like Name#1234: up to thirty characters, then four digits.",
            HttpStatus.BAD_REQUEST),

    NOT_FRIENDS(
            "SCL-BUS-007",
            "Not friends",
            "There is no accepted friendship with this user.",
            HttpStatus.NOT_FOUND),

    NO_CLOSENESS_PROPOSAL(
            "SCL-BUS-008",
            "Nothing to answer",
            "This friend has not proposed a closer relationship.",
            HttpStatus.BAD_REQUEST),

    CLOSENESS_CONFLICT(
            "SCL-BUS-009",
            "Friendship changed meanwhile",
            "The friendship was changed by the other side at the same time. Reload and try again.",
            HttpStatus.CONFLICT),

    /** Also what a private community answers to a non-member: its existence is not theirs to know. */
    COMMUNITY_NOT_FOUND(
            "SCL-BUS-010",
            "Community not found",
            "No community you can see matches the given identifier.",
            HttpStatus.NOT_FOUND),

    COMMUNITY_ADMIN_REQUIRED(
            "SCL-BUS-011",
            "Not an admin",
            "Only an admin of the community may do this.",
            HttpStatus.FORBIDDEN),

    COMMUNITY_INVALID(
            "SCL-BUS-012",
            "Invalid community",
            "A community needs a name of 3 to 60 characters, a description of at most 500, at most 5 interests of 30 characters each, and a visibility.",
            HttpStatus.BAD_REQUEST),

    /** Unknown, already used, revoked, or meant for someone else: the four read alike on purpose. */
    INVITATION_NOT_FOUND(
            "SCL-BUS-013",
            "Invitation not found",
            "This invitation does not exist, was already used or revoked, or is meant for someone else.",
            HttpStatus.NOT_FOUND),

    INVITATION_EXPIRED(
            "SCL-BUS-014",
            "Invitation expired",
            "This invitation was valid for 3 days and has expired; ask an admin of the community for a new one.",
            HttpStatus.GONE),

    INVITEE_INVALID(
            "SCL-BUS-015",
            "Cannot invite this person",
            "Invite one of your friends who is not a member yet.",
            HttpStatus.BAD_REQUEST),

    COMMUNITY_LAST_MEMBER(
            "SCL-BUS-016",
            "Last member",
            "You are the only member: leaving deletes the community. Confirm the deletion.",
            HttpStatus.CONFLICT),

    LAST_ADMIN(
            "SCL-BUS-017",
            "Last admin",
            "A community keeps at least one admin: name another admin first.",
            HttpStatus.CONFLICT),

    MEMBER_NOT_FOUND(
            "SCL-BUS-018",
            "Not a member",
            "This user is not a member of the community.",
            HttpStatus.NOT_FOUND),

    /** A public community shows its purpose to everyone, and the rest only to its members. */
    MEMBERS_ONLY(
            "SCL-BUS-019",
            "Members only",
            "Only members of the community can see this; join it first.",
            HttpStatus.FORBIDDEN),

    COMMUNITY_PICTURE_INVALID(
            "SCL-BUS-020",
            "Invalid picture",
            "The community picture must be a JPEG, PNG, GIF or BMP image.",
            HttpStatus.BAD_REQUEST),

    COMMUNITY_PICTURE_TOO_LARGE(
            "SCL-BUS-021",
            "Picture too large",
            "The community picture must be at most 10 MB.",
            HttpStatus.CONTENT_TOO_LARGE);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
