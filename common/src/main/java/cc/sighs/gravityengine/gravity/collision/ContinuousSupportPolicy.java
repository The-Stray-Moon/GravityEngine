package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.Objects;

/** Gravity-facing orientation classification. Finite foot evidence and
 * non-separation establish support; bounded follow is a separate decision. */
public final class ContinuousSupportPolicy {
    private ContinuousSupportPolicy() {}

    public enum Classification {
        FLOOR,
        CEILING,
        WALL
    }

    /**
     * Continuous-support classification of a contact by its outward normal.
     *
     * <p>{@code FLOOR} means the face may be continuously followed (normal
     * strictly exceeds the numerical entering-plane epsilon
     * of gravity-up). {@code CEILING} is the symmetric opposite. Everything
     * in between is {@code WALL}: not a continuous floor, but potentially a
     * bounded voxel terrain feature.</p>
     *
     * @param normal normalized outward contact normal (obstacle to entity)
     * @param frame  the operation's immutable gravity frame
     */
    public static Classification classify(
            Vec3d normal,
            GravityFrame frame
    ) {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(frame, "frame");
        requireNormalized(normal);

        double upDot = normal.dot(frameUp(frame));
        double threshold =
                CollisionTolerances.ENTERING_PLANE_EPSILON;
        if (upDot > threshold) {
            return Classification.FLOOR;
        }
        if (upDot < -threshold) {
            return Classification.CEILING;
        }
        return Classification.WALL;
    }

    /**
     * Pure physical orientation of a contact relative to the frame: whether
     * the face points with gravity-up, against it, or sideways. This is the
     * layer-A fact that terrain policy layers on top of; it never implies a
     * traversability decision by itself.
     */
    public static boolean facesGravityUp(
            Vec3d normal,
            GravityFrame frame
    ) {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(frame, "frame");
        return normal.dot(frameUp(frame))
                > CollisionTolerances.ENTERING_PLANE_EPSILON;
    }

    /** Neutral canonical gravity-up axis from the immutable frame orientation. */
    static Vec3d frameUp(GravityFrame frame) {
        return frame.orientation().axisY();
    }

    private static void requireNormalized(Vec3d normal) {
        if (!Double.isFinite(normal.x())
                || !Double.isFinite(normal.y())
                || !Double.isFinite(normal.z())
                || Math.abs(normal.lengthSquared() - 1.0D) > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "contact normal must be finite and normalized: " + normal
            );
        }
    }
}
