package com.calendar.social.domain.models;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;

/**
 * An accepted friendship as one side sees it.
 *
 * @param friendId         the other side
 * @param closeness        the level both have agreed on
 * @param proposal         a higher level one side asked for and the other has not answered yet, or null
 * @param proposedByFriend whether that proposal comes from the other side
 * @param version          bumped on every change, so two concurrent changes cannot both apply
 */
public record Friendship(
        String friendId,
        Closeness closeness,
        Closeness proposal,
        boolean proposedByFriend,
        long version
) {

    /**
     * What asking for {@code target} leads to: lower applies at once and drops any proposal,
     * the same level withdraws my own proposal, the level the friend already proposed is an
     * agreement, and anything higher becomes my proposal.
     */
    public Friendship requestCloseness(Closeness target) {
        if (target.isBelow(closeness)) {
            return agreedOn(target);
        }
        if (target == closeness) {
            return proposal != null && !proposedByFriend ? agreedOn(closeness) : this;
        }
        if (proposedByFriend && proposal == target) {
            return agreedOn(target);
        }
        return new Friendship(friendId, closeness, target, false, version);
    }

    public Friendship acceptProposal() {
        requireProposalFromFriend();
        return agreedOn(proposal);
    }

    public Friendship declineProposal() {
        requireProposalFromFriend();
        return agreedOn(closeness);
    }

    private Friendship agreedOn(Closeness level) {
        return new Friendship(friendId, level, null, false, version);
    }

    private void requireProposalFromFriend() {
        if (proposal == null || !proposedByFriend) {
            throw new BusinessException(BusinessErrorCode.NO_CLOSENESS_PROPOSAL);
        }
    }
}
