package cc.sighs.gravityengine.gravity.ballistic;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import java.util.Objects;

/** Pure acceleration integration. Drag, collision and control are external. */
public final class BallisticGravityIntegrator {
    private BallisticGravityIntegrator() {}

    public static Vec3d integrate(Vec3d velocity, Vec3d acceleration, double intervalTicks) {
        requireFinite(velocity, "velocity");
        requireFinite(acceleration, "acceleration");
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException("intervalTicks must be finite and positive: " + intervalTicks);
        }
        return velocity.add(acceleration.multiply(intervalTicks));
    }

    /**
     * Integrates the one immutable gravity snapshot owned by a logical
     * ballistic/passive step. The snapshot already carries the exact interval
     * that produced its effective acceleration.
     */
    public static Vec3d integrate(
            Vec3d velocity,
            GravityEvaluationSnapshot evaluation
    ) {
        Objects.requireNonNull(evaluation, "evaluation");
        return integrate(
                velocity,
                evaluation.effectiveAcceleration(),
                evaluation.intervalTicks()
        );
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
