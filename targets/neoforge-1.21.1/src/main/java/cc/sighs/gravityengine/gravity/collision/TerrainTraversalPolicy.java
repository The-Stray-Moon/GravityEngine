package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;

/**
 * Central policy for custom-gravity terrain traversal and support semantics.
 *
 * <p>Traversability is intentionally layered instead of being one hard
 * slope threshold:</p>
 *
 * <ol>
 *   <li>physical contact orientation (which faces toward gravity-up,
 *       which faces away, which are side faces) is decided by the contact
 *       normal against the frozen operation {@link GravityFrame};</li>
 *   <li>ordinary continuous support-following is a fast path gated by
 *       {@link #MIN_CONTINUOUS_SUPPORT_UP_DOT} and its implied
 *       {@link #MAX_CONTINUOUS_SUPPORT_RISE_RATIO};</li>
 *   <li>finite static voxel terrain features that the continuous fast path
 *       declines may still be traversed by the bounded rise-&gt;traverse-&gt;settle
 *       route, capped by the entity's authored step height and by an actual
 *       legal final landing.</li>
 * </ol>
 *
 * <p>{@code 0.60} no longer decides whether a finite voxel feature is
 * traversable at all. Below the threshold a contact is not a continuous
 * slope; it becomes a bounded terrain-feature candidate (static voxel
 * geometry only), and only surfaces that remain unsupported by that route
 * are ordinary walls.</p>
 *
 * <p>Arbitrary gravity rotates the step axis into the operation frame; it
 * never grants a larger stepping capability. Every gravity direction uses the
 * authored {@code Entity.maxUpStep()} rise budget, so default-gravity parity
 * and custom-gravity semantics stay aligned.</p>
 */
public final class TerrainTraversalPolicy {
    /**
     * Minimum {@code normal dot frame.up()} for ordinary continuous
     * support-following. A 0.60 threshold admits a continuous slope of up to
     * 53.13 degrees; it is the fast-path boundary only and never the absolute
     * traversability boundary for finite voxel terrain.
     */
    public static final double MIN_CONTINUOUS_SUPPORT_UP_DOT = 0.60D;

    /**
     * Largest gravity-up rise per unit of tangent movement accepted by the
     * continuous support-follow fast path:
     * {@code sqrt(1 - c^2) / c} for {@code c = 0.60} (~1.3333).
     */
    public static final double MAX_CONTINUOUS_SUPPORT_RISE_RATIO =
            Math.sqrt(
                    1.0D
                            - MIN_CONTINUOUS_SUPPORT_UP_DOT
                            * MIN_CONTINUOUS_SUPPORT_UP_DOT
            ) / MIN_CONTINUOUS_SUPPORT_UP_DOT;

    /** World-axis alignment tolerance used by voxel manifold membership. */
    static final double AXIS_ALIGNMENT_EPSILON = 1.0E-9D;

    private TerrainTraversalPolicy() {}

    /**
     * Operation-local terrain rise capability for an already-established
     * custom-gravity movement operation.
     *
     * <p>Arbitrary gravity changes the direction of the step, not the size of
     * the capability. The bounded voxel-terrain route therefore receives
     * exactly the authored entity step height; a static-voxel contact
     * provenance never raises it.</p>
     */
    public static double customGravityTerrainRiseBudget(
            double authoredStepHeight
    ) {
        if (!Double.isFinite(authoredStepHeight)) {
            throw new IllegalArgumentException(
                    "authoredStepHeight must be finite: "
                            + authoredStepHeight
            );
        }

        return Math.max(0.0D, authoredStepHeight);
    }

    /**
     * Static voxel terrain identity.
     *
     * <p>Only a world-axis contact plane produced by an actual BlockObstacle may
     * use the relaxed terrain traversal policy.</p>
     */
    public static boolean isStaticVoxelTerrainContact(
            CollisionContact contact
    ) {
        if (contact == null) {
            return false;
        }

        return contact.obstacle()
                instanceof BlockObstacle
                && isWorldAxisNormal(
                contact.normal()
        );
    }

    /**
     * Physical operation-local terrain support.
     *
     * <p>This is intentionally weaker than continuous-slope eligibility.
     * A static voxel face only needs a positive gravity-up component to support a
     * body against gravity. Whether locomotion may continuously follow that face
     * is still decided separately by ContinuousSupportPolicy; a steep or vertical
     * voxel wall does NOT become continuously climbable merely because it can
     * support the body.</p>
     */
    public static boolean isStaticVoxelTerrainSupport(
            CollisionContact contact,
            GravityFrame frame
    ) {
        Objects.requireNonNull(
                frame,
                "frame"
        );

        return isStaticVoxelTerrainContact(contact)
                && contact.normal().dot(
                        frame.orientation().axisY(new Vector3d()))
                > CollisionTolerances.ENTERING_PLANE_EPSILON;
    }

    public static boolean isWorldAxisNormal(
            Vector3d normal
    ) {
        return worldAxisDimension(normal) >= 0;
    }

    /**
     * Returns true only when the complete blocking provenance belongs to static
     * world-axis voxel geometry.
     *
     * <p>An "exists voxel blocker" test is insufficient: a mixed voxel/entity or
     * voxel/non-voxel manifold must not gain static-voxel traversal treatment
     * from one incidental voxel contact.</p>
     */
    public static boolean exclusivelyStaticVoxelBlockers(
            List<CollisionContact> contacts
    ) {
        if (contacts == null
                || contacts.isEmpty()) {
            return false;
        }

        for (CollisionContact contact : contacts) {
            if (!isStaticVoxelTerrainContact(
                    contact
            )) {
                return false;
            }
        }

        return true;
    }
    /**
     * X=0, Y=1, Z=2 only for a numerically world-axis unit normal; -1
     * otherwise. Used by voxel manifold recognition so contact search stays
     * bounded to the three world axes.
     */
    static int worldAxisDimension(Vector3d normal) {
        Objects.requireNonNull(normal, "normal");
        double absX = Math.abs(normal.x);
        double absY = Math.abs(normal.y);
        double absZ = Math.abs(normal.z);

        if (absX >= 1.0D - AXIS_ALIGNMENT_EPSILON
                && absY <= AXIS_ALIGNMENT_EPSILON
                && absZ <= AXIS_ALIGNMENT_EPSILON) {
            return 0;
        }

        if (absY >= 1.0D - AXIS_ALIGNMENT_EPSILON
                && absX <= AXIS_ALIGNMENT_EPSILON
                && absZ <= AXIS_ALIGNMENT_EPSILON) {
            return 1;
        }

        if (absZ >= 1.0D - AXIS_ALIGNMENT_EPSILON
                && absX <= AXIS_ALIGNMENT_EPSILON
                && absY <= AXIS_ALIGNMENT_EPSILON) {
            return 2;
        }

        return -1;
    }
}
