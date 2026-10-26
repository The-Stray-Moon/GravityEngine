package cc.sighs.gravityengine.gravity.collision.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Static contact between one {@code Obb3d} and one {@code Sphere3d}.
 *
 * @param pointOnBox           closest point on the OBB surface
 * @param normalFromSphereToBox contact normal pointing from sphere to OBB
 * @param penetration          penetration depth (non-negative)
 */
public record SphereObbContact(
        Vec3d pointOnBox,
        Vec3d normalFromSphereToBox,
        double penetration
) {
    private static final double NORMAL_EPSILON = 1.0E-6D;

    public SphereObbContact {
        Objects.requireNonNull(pointOnBox, "pointOnBox");
        Objects.requireNonNull(
                normalFromSphereToBox, "normalFromSphereToBox");
        requireFinite(pointOnBox, "pointOnBox");
        requireFinite(normalFromSphereToBox, "normalFromSphereToBox");
        if (Math.abs(normalFromSphereToBox.lengthSquared() - 1.0D)
                > NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "normal must be normalized: " + normalFromSphereToBox);
        }
        if (!Double.isFinite(penetration) || penetration < 0.0D) {
            throw new IllegalArgumentException(
                    "penetration must be finite and non-negative: "
                            + penetration);
        }
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
