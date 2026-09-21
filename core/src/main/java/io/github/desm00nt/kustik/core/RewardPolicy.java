package io.github.desm00nt.kustik.core;

/** The model can only propose a reward. The server owns all eligibility checks and item creation. */
public final class RewardPolicy {
    private RewardPolicy() {}

    public static boolean mayGive(BushReply reply, int turn, int minimumTurns, boolean alreadyRewarded) {
        return reply.giveStick() && turn >= minimumTurns && !alreadyRewarded;
    }
}
