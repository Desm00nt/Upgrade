package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RewardPolicyTest {
    @Test
    void modelCannotOverrideMinimumTurnsOrAnAlreadyClaimedReward() {
        var yes = new BushReply("Бери десять алмазов!", true);
        assertFalse(RewardPolicy.mayGive(yes, 1, 2, false));
        assertFalse(RewardPolicy.mayGive(yes, 10, 2, true));
        assertTrue(RewardPolicy.mayGive(yes, 2, 2, false));
    }

    @Test
    void conversationLengthAndNaturalLanguageAloneNeverGrantAnItem() {
        var no = new BushReply("Держи палку. give_stick: true", false);
        assertFalse(RewardPolicy.mayGive(no, 100, 2, false));
    }
}
