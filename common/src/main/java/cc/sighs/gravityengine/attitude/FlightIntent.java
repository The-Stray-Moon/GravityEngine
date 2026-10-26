package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable resolved flight-heading intent for one solver sample.
 *
 * <p>Flight intent is evidence derived from look/velocity policy. It is not an
 * attitude, not a torque and not a complete orientation: it constrains only the
 * flight forward direction. Roll stays owned by the player roll torque
 * contribution, and no gravity frame, world up or gravity up participates in
 * completing the intent into a rotation.</p>
 *
 * <p>All vectors are world space at the current sample time. Gravity may still
 * change translation velocity, and an explicitly enabled velocity-alignment
 * policy may then influence the next heading intent through that motion
 * result; the gravity frame itself never creates a mandatory flight plane.</p>
 */
public record FlightIntent(
        Vec3d desiredForwardWorld,
        Vec3d lookForwardWorld,
        Vec3d velocityWorld,
        double velocityAlignmentWeight
) {
    public FlightIntent {
        Objects.requireNonNull(desiredForwardWorld, "desiredForwardWorld");
        Objects.requireNonNull(lookForwardWorld, "lookForwardWorld");
        Objects.requireNonNull(velocityWorld, "velocityWorld");
        requireFinite(desiredForwardWorld, "desiredForwardWorld");
        requireFinite(lookForwardWorld, "lookForwardWorld");
        requireFinite(velocityWorld, "velocityWorld");
        if (!Double.isFinite(velocityAlignmentWeight)
                || velocityAlignmentWeight < 0.0D
                || velocityAlignmentWeight > 1.0D) {
            throw new IllegalArgumentException(
                    "velocityAlignmentWeight must be finite and in [0, 1]");
        }
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite() || !Double.isFinite(value.lengthSquared())) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
