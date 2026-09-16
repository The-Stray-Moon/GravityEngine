package cc.sighs.gravityengine.gravity.collision.geometry;

import org.joml.Vector3d;

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
        Vector3d normalFromSphereToBox,
        Vector3d contactPoint
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
        normalFromSphereToBox = new Vector3d(normalFromSphereToBox);
        contactPoint = new Vector3d(contactPoint);
    }

    @Override
    public Vector3d normalFromSphereToBox() {
        return new Vector3d(normalFromSphereToBox);
    }

    @Override
    public Vector3d contactPoint() {
        return new Vector3d(contactPoint);
    }

    private static void requireFinite(Vector3d vector, String name) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
