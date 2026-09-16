package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Continuous-support fast-path classification.
 *
 * <p>This policy answers only one question: may this contact be followed as an
 * ordinary continuous slope by the current frozen physics frame? The old
 * single-threshold {@code FLOOR} / {@code WALL} boundary has been demoted to
 * that fast-path boundary; it no longer decides the absolute traversability of
 * finite voxel terrain (see {@link TerrainTraversalPolicy}).</p>
 *
 * <p>{@link Classification#WALL} therefore means "not an ordinary continuous
 * slope under this frame": a sub-threshold face that still faces gravity-up is
 * a bounded terrain-feature candidate rather than a permanently blocking wall,
 * while a ceiling-facing or true side contact remains physically blocking.
 * The classification never resamples gravity and never reads historical
 * support state.</p>
 */
public final class ContinuousSupportPolicy {
    /**
     * Boundary is owned by {@link TerrainTraversalPolicy}; this class only
     * consumes it so there is exactly one slope-constant source.
     */
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
     * carries at least {@link TerrainTraversalPolicy#MIN_CONTINUOUS_SUPPORT_UP_DOT}
     * of gravity-up). {@code CEILING} is the symmetric opposite. Everything
     * in between is {@code WALL}: not a continuous floor, but potentially a
     * bounded voxel terrain feature.</p>
     *
     * @param normal normalized outward contact normal (obstacle to entity)
     * @param frame  the operation's immutable gravity frame
     */
    public static Classification classify(
            Vector3d normal,
            GravityFrame frame
    ) {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(frame, "frame");
        requireNormalized(normal);

        double upDot = normal.dot(frameUp(frame));
        double threshold =
                TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT;
        if (upDot >= threshold) {
            return Classification.FLOOR;
        }
        if (upDot <= -threshold) {
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
            Vector3d normal,
            GravityFrame frame
    ) {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(frame, "frame");
        return normal.dot(frameUp(frame))
                > CollisionTolerances.ENTERING_PLANE_EPSILON;
    }

    /** Neutral canonical gravity-up axis (JOML copy from the frame orientation). */
    static Vector3d frameUp(GravityFrame frame) {
        return frame.orientation().axisY(new Vector3d());
    }

    private static void requireNormalized(Vector3d normal) {
        if (!Double.isFinite(normal.x)
                || !Double.isFinite(normal.y)
                || !Double.isFinite(normal.z)
                || Math.abs(normal.lengthSquared() - 1.0D) > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "contact normal must be finite and normalized: " + normal
            );
        }
    }
}
