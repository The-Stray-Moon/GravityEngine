package cc.sighs.gravityengine.gravity.collision.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Translational sweep hit between a moving OBB and a sphere.
 *
 * @param timeOfImpact          earliest valid time in [0, 1]
 * @param normalFromSphereToBox contact normal pointing from sphere to OBB
 * @param contactPoint          world-space contact point
 */
public record SphereObbSweepHit(
        double timeOfImpact,
        Vec3d normalFromSphereToBox,
        Vec3d contactPoint
) {
    private static final double NORMAL_EPSILON = 1.0E-6D;

    public SphereObbSweepHit {
        if (!Double.isFinite(timeOfImpact)
                || timeOfImpact < 0.0D
                || timeOfImpact > 1.0D) {
            throw new IllegalArgumentException(
                    "timeOfImpact must be in [0, 1]: " + timeOfImpact);
        }
        Objects.requireNonNull(
                normalFromSphereToBox, "normalFromSphereToBox");
        Objects.requireNonNull(contactPoint, "contactPoint");
        requireFinite(normalFromSphereToBox, "normalFromSphereToBox");
        requireFinite(contactPoint, "contactPoint");
        if (Math.abs(normalFromSphereToBox.lengthSquared() - 1.0D)
                > NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "normal must be normalized: " + normalFromSphereToBox);
        }
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
