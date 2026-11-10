package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable attitude-policy result and diagnostics.
 *
 * <p>{@code appliedTorqueWorld} is the net world-space torque of the final
 * solver substep in {@code inertia-unit * rad / s^2}. Angular acceleration is
 * not stored: it is derived from torque and the installed effective inertia,
 * and only when the resulting state still has dynamic ownership.</p>
 *
 * <p>Dynamic trajectory segments record the angular momentum samples produced
 * by the solver; kinematic segments record a mode-owned geometric orientation
 * rewrite without creating or transferring angular momentum. This trajectory
 * never owns character collision geometry or translation.</p>
 */
public record BodyAttitudeStepResult(
        BodyAttitudeState nextState,
        Vec3d appliedTorqueWorld,
        double rotationErrorRadians,
        int substepCount,
        boolean timeClamped,
        AngularTrajectory trajectory
) {
    public BodyAttitudeStepResult {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(appliedTorqueWorld,
                "appliedTorqueWorld");
        Objects.requireNonNull(trajectory, "trajectory");
        if (!Double.isFinite(rotationErrorRadians)
                || !Double.isFinite(appliedTorqueWorld.x())
                || !Double.isFinite(appliedTorqueWorld.y())
                || !Double.isFinite(appliedTorqueWorld.z())) {
            throw new IllegalArgumentException("step diagnostics must be finite");
        }
        if (rotationErrorRadians < 0.0D) {
            throw new IllegalArgumentException(
                    "rotationErrorRadians must be non-negative");
        }
        if (substepCount < 0) {
            throw new IllegalArgumentException(
                    "substepCount must be non-negative");
        }
        if (trajectory.dynamicSegmentCount() != substepCount) {
            throw new IllegalArgumentException(
                    "trajectory dynamic segment count must equal substepCount: "
                            + "dynamicSegments="
                            + trajectory.dynamicSegmentCount()
                            + ", substeps=" + substepCount
            );
        }
    }

    /** Returns a defensive copy of the real per-substep trajectory. */
    @Override
    public AngularTrajectory trajectory() {
        return trajectory.copy();
    }

    /**
     * Derived diagnostic angular acceleration in rad/s^2, or {@code ZERO} for
     * kinematic ownership where no physical rotation rate exists.
     */
    public Vec3d derivedAngularAccelerationWorld() {
        return nextState.effectiveInertia()
                .map(inertia -> appliedTorqueWorld.multiply(
                        1.0D / inertia.isotropic()))
                .orElse(Vec3d.ZERO);
    }
}
