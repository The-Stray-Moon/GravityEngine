package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.List;
import java.util.Objects;

/** Positive gravity-up foot contacts may support at any slope. Auxiliary
 * following is bounded separately; discrete stepping retains authored height.
 */
public final class TerrainTraversalPolicy {
    /** Auxiliary displacement per requested tangent length, not a slope limit. */
    public static final double MAX_SUPPORT_FOLLOW_RATIO = 4.0D / 3.0D;

    /** Numerical allowance shared by route and scene capture. */
    public static final double SUPPORT_FOLLOW_ALLOWANCE = 1.0E-6D;

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
                        frame.orientation().axisY())
                > CollisionTolerances.ENTERING_PLANE_EPSILON;
    }

    public static boolean isWorldAxisNormal(
            Vec3d normal
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
    static int worldAxisDimension(Vec3d normal) {
        Objects.requireNonNull(normal, "normal");
        double absX = Math.abs(normal.x());
        double absY = Math.abs(normal.y());
        double absZ = Math.abs(normal.z());

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
