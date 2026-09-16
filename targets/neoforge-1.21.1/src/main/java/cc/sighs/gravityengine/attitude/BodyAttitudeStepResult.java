package cc.sighs.gravityengine.attitude;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Immutable actor-controller result and diagnostics. Dynamic segments record angular integration;
 * kinematic segments record view-joint corrections without creating angular momentum.
 * This trajectory never owns character collision geometry or translation. */
public record BodyAttitudeStepResult(
        BodyAttitudeState nextState,
        Vec3 appliedAngularAccelerationWorld,
        double rotationErrorRadians,
        int substepCount,
        boolean timeClamped,
        AngularTrajectory trajectory
) {
    public BodyAttitudeStepResult {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(appliedAngularAccelerationWorld,
                "appliedAngularAccelerationWorld");
        Objects.requireNonNull(trajectory, "trajectory");
        if (!Double.isFinite(rotationErrorRadians)
                || !Double.isFinite(appliedAngularAccelerationWorld.x)
                || !Double.isFinite(appliedAngularAccelerationWorld.y)
                || !Double.isFinite(appliedAngularAccelerationWorld.z)) {
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

}
