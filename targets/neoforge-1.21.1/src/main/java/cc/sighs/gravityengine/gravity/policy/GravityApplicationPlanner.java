package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.model.*;

import java.util.Objects;

/**
 * Pure capability + current-state to active-plan policy.
 *
 * <p>The planner consumes only immutable semantic facts: entity-kind
 * capabilities, the authority mode, whether an explicit DIRECT assignment or
 * an active FIELD contribution exists, suppression, and one {@link
 * EntityState} snapshot.  Minecraft/runtime state capture lives in
 * {@code gravity.integration.GravityApplicationStateCapture}; this type must
 * never read a live {@code Entity}.</p>
 */
public final class GravityApplicationPlanner {
    private GravityApplicationPlanner() {}

    /**
     * Pure current-entity-state snapshot used by the planner.  Actual fluid
     * locomotion (in water/lava/another fluid type) and swimming presentation
     * (Vanilla swimming pose/flag) are separate exclusions. GravityEngine's
     * SWIM_ACTION model animation never sets either of these fields.
     */
    public record EntityState(
            boolean noPhysics,
            boolean spectator,
            boolean passenger,
            boolean sleeping,
            boolean fallFlying,
            boolean actualFluidLocomotion,
            boolean swimmingPresentation,
            boolean autoSpinAttack,
            boolean climbing,
            boolean deadOrDying,
            boolean controlledFlight,
            boolean upsideDownPresentation,
            boolean otherVanillaPose
    ) {
        public static EntityState ordinary() {
            return new EntityState(
                    false, false, false, false, false,
                    false, false, false, false,
                    false, false, false, false);
        }

    }

    /**
     * Selects the validated active application tuple from immutable inputs.
     *
     * <p>{@code hasExplicitState} means a DIRECT assignment exists; it is
     * never inferred from the assigned state's numeric value, so an explicit
     * DIRECT assignment that numerically equals default gravity still owns
     * DIRECT acceleration semantics.  {@code hasActiveField} means the
     * resolved environmental evidence contains at least one contribution in
     * the effective gravity-field composition group. It does not imply that
     * the resultant differs numerically from the default Vanilla gravity
     * vector. When no active field exists, Vanilla/default field authority
     * may remain applicable; when an active field exists, FIELD authority is
     * present even if the resultant numerically equals default gravity or is
     * exactly zero.</p>
     */
    public static GravityApplicationPlan plan(
            GravityEntityCapabilities capabilities,
            GravityAuthorityMode authority,
            boolean hasExplicitState,
            boolean hasActiveField,
            boolean suppressed,
            EntityState state
    ) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(state, "state");

        if (state.noPhysics() || state.spectator() || state.passenger()) {
            return GravityApplicationPlan.vanilla();
        }

        if (capabilities == GravityEntityCapabilities.BALLISTIC
                || capabilities == GravityEntityCapabilities.PASSIVE_BALLISTIC) {
            GravityAccelerationMode acceleration = accelerationMode(
                    capabilities, authority
            );
            return (capabilities == GravityEntityCapabilities.PASSIVE_BALLISTIC
                    ? GravityApplicationPlan.passive(acceleration)
                    : GravityApplicationPlan.ballistic(acceleration));
        }

        // This is an explicit architectural blocker. Flight compensation,
        // hover thrust and FlyingMoveControl are not represented by the
        // character ground/air kernel.
        if (capabilities == GravityEntityCapabilities.FLYING_BLOCKER
                || capabilities == GravityEntityCapabilities.VANILLA_SPECIAL) {
            return GravityApplicationPlan.vanilla();
        }

        if (capabilities != GravityEntityCapabilities.CHARACTER
                || suppressed
                || (authority == GravityAuthorityMode.DIRECT && !hasExplicitState)
                || (authority == GravityAuthorityMode.FIELD && !hasActiveField)
                || state.sleeping()
                || state.actualFluidLocomotion()
                || state.swimmingPresentation()
                || state.autoSpinAttack() || state.climbing()
                || state.deadOrDying() || state.controlledFlight()
                || state.upsideDownPresentation() || state.otherVanillaPose()) {
            return GravityApplicationPlan.vanilla();
        }

        return GravityApplicationPlan.character(
                accelerationMode(capabilities, authority)
        );
    }

    private static GravityAccelerationMode accelerationMode(
            GravityEntityCapabilities capabilities,
            GravityAuthorityMode authority
    ) {
        GravityAccelerationMode mode = authority == GravityAuthorityMode.DIRECT
                ? GravityAccelerationMode.DIRECT
                : GravityAccelerationMode.FIELD;
        return capabilities.supports(mode) ? mode : GravityAccelerationMode.NONE;
    }
}
