package cc.sighs.gravityengine.gravity.movement;

/**
 * Player-owned transient resolved mode.
 *
 * No keyboard history, attitude or disk lifetime.
 *
 * Swim activation requires two consecutive eligible logical steps so a
 * one-step support glitch cannot flash FREE_3D / SWIM_ACTION.
 */
public final class CharacterControlMode {
    private static final int SWIM_ACTIVATION_CONFIRM_STEPS =
            2;

    private boolean swimActive;

    private int consecutiveEligibleSteps;

    private boolean pendingSwimToggle;

    public boolean swimActive() {
        return swimActive;
    }

    /**
     * Called once for each newly resolved logical step.
     */
    public void retainIfEligible(
            boolean eligible
    ) {
        retainIfEligible(eligible, eligible);
    }

    /** Unknown support may retain active ownership, but breaks activation proof. */
    public void retainIfEligible(boolean eligible, boolean mayContinue) {
        if (!eligible && mayContinue) {
            consecutiveEligibleSteps = 0;
            pendingSwimToggle = false;
            return;
        }
        if (!eligible) {
            swimActive = false;
            consecutiveEligibleSteps = 0;
            pendingSwimToggle = false;
            return;
        }

        if (consecutiveEligibleSteps
                < SWIM_ACTIVATION_CONFIRM_STEPS) {

            consecutiveEligibleSteps++;
        }

        /*
         * The user pressed sprint during the first valid airborne step.
         * Activate only if the next logical step independently confirms the
         * same state.
         */
        if (pendingSwimToggle
                && consecutiveEligibleSteps
                >= SWIM_ACTIVATION_CONFIRM_STEPS) {

            swimActive = true;
            pendingSwimToggle = false;
        }
    }

    /**
     * Sprint itself remains Vanilla-owned.
     *
     * This consumes only GravityEngine's observed sprint press as the explicit
     * low-gravity swim toggle.
     */
    public void pressSprintIntent(
            boolean eligible
    ) {
        /*
         * An already active swim can always be explicitly switched off.
         */
        if (swimActive) {
            swimActive = false;
            pendingSwimToggle = false;
            return;
        }

        if (!eligible) {
            pendingSwimToggle = false;
            return;
        }

        if (consecutiveEligibleSteps
                >= SWIM_ACTIVATION_CONFIRM_STEPS) {

            swimActive = true;
            pendingSwimToggle = false;

        } else {
            /*
             * First confirmed airborne step. Hold the user's request but do
             * not change locomotion/presentation yet.
             */
            pendingSwimToggle = true;
        }
    }

    public void installReplicated(
            boolean active
    ) {
        swimActive = active;
        pendingSwimToggle = false;

        consecutiveEligibleSteps =
                active
                        ? SWIM_ACTIVATION_CONFIRM_STEPS
                        : 0;
    }

    public void clear() {
        swimActive = false;
        consecutiveEligibleSteps = 0;
        pendingSwimToggle = false;
    }
}
