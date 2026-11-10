package cc.sighs.gravityengine.attitude;

import java.util.Objects;

/** One tick's actor-control profile. Free attitude follows the independent quaternion controller;
 * Elytra consumes flight alignment weight. REFERENCE_ALIGNED resolves INACTIVE ownership
 * and never runs the actor controller. */
public record BodyAttitudeControlProfile(
        BodyAttitudeConstraintKind constraint,
        double flightAlignmentWeight,
        BodyAttitudeControlAuthority userControl
) {
    public BodyAttitudeControlProfile {
        Objects.requireNonNull(constraint, "constraint");
        flightAlignmentWeight = unit(
                flightAlignmentWeight, "flightAlignmentWeight");
        Objects.requireNonNull(userControl, "userControl");
    }

    private static double unit(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
