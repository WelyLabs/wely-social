package com.calendar.social.domain.models;

import com.calendar.social.exception.BusinessErrorCode;
import com.calendar.social.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static com.calendar.social.domain.models.Closeness.CLOSE;
import static com.calendar.social.domain.models.Closeness.FRIEND;
import static com.calendar.social.domain.models.Closeness.INTIMATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipTest {

    private static Friendship agreed(Closeness level) {
        return new Friendship("bob", level, null, false, 3, null);
    }

    private static Friendship proposed(Closeness level, Closeness proposal, boolean byFriend) {
        return new Friendship("bob", level, proposal, byFriend, 3, null);
    }

    @Test
    void requestCloseness_shouldProposeAHigherLevelWithoutApplyingIt() {
        assertThat(agreed(FRIEND).requestCloseness(INTIMATE)).isEqualTo(proposed(FRIEND, INTIMATE, false));
    }

    @Test
    void requestCloseness_shouldStepDownAtOnce_whenLower() {
        assertThat(agreed(INTIMATE).requestCloseness(FRIEND)).isEqualTo(agreed(FRIEND));
    }

    @Test
    void requestCloseness_shouldDropAPendingProposal_whenSteppingDown() {
        assertThat(proposed(CLOSE, INTIMATE, true).requestCloseness(FRIEND)).isEqualTo(agreed(FRIEND));
    }

    @Test
    void requestCloseness_shouldAgree_whenAskingWhatTheFriendProposed() {
        assertThat(proposed(FRIEND, CLOSE, true).requestCloseness(CLOSE)).isEqualTo(agreed(CLOSE));
    }

    @Test
    void requestCloseness_shouldReplaceTheFriendsProposal_whenAskingForAnotherHigherLevel() {
        assertThat(proposed(FRIEND, CLOSE, true).requestCloseness(INTIMATE)).isEqualTo(proposed(FRIEND, INTIMATE, false));
    }

    @Test
    void requestCloseness_shouldWithdrawMyProposal_whenAskingForTheCurrentLevel() {
        assertThat(proposed(FRIEND, CLOSE, false).requestCloseness(FRIEND)).isEqualTo(agreed(FRIEND));
    }

    @Test
    void requestCloseness_shouldKeepTheFriendsProposal_whenAskingForTheCurrentLevel() {
        Friendship pending = proposed(FRIEND, CLOSE, true);

        assertThat(pending.requestCloseness(FRIEND)).isSameAs(pending);
    }

    @Test
    void requestCloseness_shouldKeepTheVersion() {
        assertThat(agreed(FRIEND).requestCloseness(CLOSE).version()).isEqualTo(3);
    }

    @Test
    void acceptProposal_shouldApplyTheFriendsProposal() {
        assertThat(proposed(FRIEND, INTIMATE, true).acceptProposal()).isEqualTo(agreed(INTIMATE));
    }

    @Test
    void acceptProposal_shouldRefuse_whenTheProposalIsMine() {
        assertThatThrownBy(() -> proposed(FRIEND, CLOSE, false).acceptProposal())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BusinessErrorCode.NO_CLOSENESS_PROPOSAL));
    }

    @Test
    void acceptProposal_shouldRefuse_whenNothingIsProposed() {
        assertThatThrownBy(() -> agreed(FRIEND).acceptProposal()).isInstanceOf(BusinessException.class);
    }

    @Test
    void declineProposal_shouldKeepTheLevelAndClearTheProposal() {
        assertThat(proposed(CLOSE, INTIMATE, true).declineProposal()).isEqualTo(agreed(CLOSE));
    }

    @Test
    void declineProposal_shouldRefuse_whenNothingIsProposedByTheFriend() {
        assertThatThrownBy(() -> proposed(FRIEND, CLOSE, false).declineProposal())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BusinessErrorCode.NO_CLOSENESS_PROPOSAL));
    }

    @Test
    void requestCloseness_shouldKeepTheFriendshipDate() {
        java.time.Instant since = java.time.Instant.parse("2025-01-02T03:04:05Z");
        Friendship friendship = new Friendship("bob", FRIEND, null, false, 3, since);

        assertThat(friendship.requestCloseness(CLOSE).since()).isEqualTo(since);
        assertThat(friendship.requestCloseness(CLOSE).requestCloseness(FRIEND).since()).isEqualTo(since);
    }
}
