package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.attitude.BodyAttitudePlayerState;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;

/**
 * Explicit special-mode policy. Ordinary ground/air motion — standing,
 * walking, sprinting, crouching, jumping, falling and weak/zero-gravity drift
 * — is always {@code GROUND_AIR / GRAVITY_TANGENT / REFERENCE_ALIGNED / NORMAL}.
 *
 * <p>Only an explicitly selected and currently eligible low-gravity swim
 * request releases the ordinary upright constraint. Elytra keeps its own
 * contract and takes priority over swim.</p>
 *
 * <p>Endpoint support owns swim eligibility; gameplay ground remains a
 * separate Vanilla fact. Unknown endpoint state permits the receiving
 * weak-gravity swim contract to bootstrap; it never manufactures support from
 * Vanilla carriers. Losing support or crossing a gravity threshold is never by
 * itself a request for free attitude.</p>
 */
public final class CharacterLocomotionControlPolicyResolver {
    private CharacterLocomotionControlPolicyResolver() {}

    public static CharacterControlPlan resolve(
            BodyAttitudePlayerState state,
            boolean eligible,
            boolean resolvedSwimMode,
            CharacterGroundFacts groundFacts,
            GravityFrame frame,
            BodyAttitudeConfigSnapshot config
    ) {
        if (eligible && state.fallFlying()) {
            return new CharacterControlPlan(
                    CharacterLocomotionTechnique.ELYTRA,
                    CharacterControlBasis.GRAVITY_TANGENT,
                    CharacterAttitudeContract.ELYTRA_ALIGNED,
                    CharacterPresentationIntent.ELYTRA);
        }

        boolean swimActive = resolvedSwimMode
                && lowGravitySwimEligible(state, eligible, groundFacts, frame, config);

        return new CharacterControlPlan(
                swimActive ? CharacterLocomotionTechnique.FREE_3D
                        : CharacterLocomotionTechnique.GROUND_AIR,
                swimActive ? CharacterControlBasis.VIEW_3D
                        : CharacterControlBasis.GRAVITY_TANGENT,
                swimActive ? CharacterAttitudeContract.FREE_ATTITUDE
                        : CharacterAttitudeContract.REFERENCE_ALIGNED,
                swimActive ? CharacterPresentationIntent.SWIM_ACTION
                        : CharacterPresentationIntent.NORMAL);
    }

    /** Low-gravity swim eligibility only; never a statement that an ordinary
     * body may leave its gravity reference. */
    public static boolean lowGravitySwimEligible(
            BodyAttitudePlayerState state,
            boolean eligible,
            CharacterGroundFacts groundFacts,
            GravityFrame frame,
            BodyAttitudeConfigSnapshot config
    ) {
        return eligible
                && !state.fallFlying()
                && !groundFacts
                .gameplayGroundedAtStepStart()
                && groundFacts
                .terminalExplicitlyUnsupportedAtStepStart()
                && frame.strength()
                < GravityState.VANILLA_STRENGTH
                * config.lowGravitySwimThresholdRatio();
    }
}
