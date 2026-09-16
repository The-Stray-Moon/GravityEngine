package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.*;

import java.util.Objects;
import java.util.Optional;

/** Vanilla exclusions first; the character plan supplies explicit active attitude authority. */
public final class BodyAttitudeControlPolicyResolver {
    private BodyAttitudeControlPolicyResolver() {}

    /**
     * Phase A: returns a suspended decision for temporary Vanilla exclusions,
     * or empty when the player may own attitude presentation.
     */
    public static Optional<BodyAttitudeDecision> findSuspension(
            BodyAttitudePlayerState state
    ) {
        Objects.requireNonNull(state, "state");
        if (state.noPhysics()) return suspended(BodyAttitudeSuspensionReason.NO_PHYSICS);
        if (state.spectator()) return suspended(BodyAttitudeSuspensionReason.SPECTATOR);
        if (state.passenger()) return suspended(BodyAttitudeSuspensionReason.PASSENGER);
        if (state.sleeping()) return suspended(BodyAttitudeSuspensionReason.SLEEPING);
        if (state.deadOrDying()) return suspended(BodyAttitudeSuspensionReason.DEAD_OR_DYING);
        if (state.actualFluidLocomotion()
                || state.swimmingPresentation()) {
            return suspended(BodyAttitudeSuspensionReason.SWIMMING_OR_FLUID_POSE);
        }
        if (state.autoSpinAttack()) {
            return suspended(BodyAttitudeSuspensionReason.AUTO_SPIN_ATTACK);
        }
        if (state.climbing()) return suspended(BodyAttitudeSuspensionReason.CLIMBING);
        if (state.controlledFlight()) {
            return suspended(BodyAttitudeSuspensionReason.CONTROLLED_FLIGHT);
        }
        if (state.upsideDownPresentation()) {
            return suspended(BodyAttitudeSuspensionReason.UPSIDE_DOWN_PRESENTATION);
        }
        if (state.otherVanillaPose()) {
            return suspended(BodyAttitudeSuspensionReason.OTHER_VANILLA_POSE);
        }
        return Optional.empty();
    }

    /** Resolves only the attitude profile; locomotion has already selected its contract. */
    public static BodyAttitudeDecision resolveActive(cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract contract) {
        if (contract.elytraAlignment()) return BodyAttitudeDecision.active(new BodyAttitudeControlProfile(
                BodyAttitudeConstraintKind.ELYTRA_ALIGNED, 1, contract.inputAuthority()));
        return BodyAttitudeDecision.active(new BodyAttitudeControlProfile(
                contract == cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.REFERENCE_ALIGNED
                        ? BodyAttitudeConstraintKind.REFERENCE_ALIGNED : BodyAttitudeConstraintKind.FREE_ATTITUDE, 0, contract.inputAuthority()));
    }

    private static Optional<BodyAttitudeDecision> suspended(
            BodyAttitudeSuspensionReason reason
    ) {
        return Optional.of(BodyAttitudeDecision.suspended(reason));
    }
}
