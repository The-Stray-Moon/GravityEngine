package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;

/**
 * Shared numerical tolerances for the character kinematic pipeline.
 *
 * <p>The values use Minecraft block units and double precision. They are
 * intentionally separate because geometric skin, event-time comparison,
 * plane classification, and support classification have different meanings.</p>
 */
public final class CollisionTolerances {
    /** Squared axis length below which a SAT cross axis is geometrically degenerate. */
    public static final double GEOMETRIC_AXIS_EPSILON = 1.0E-12D;

    /**
     * World-space separation/overlap band treated as contact at t=0.
     * This equals {@link #PENETRATION_EPSILON}: shallow overlap is therefore
     * owned by touching CCD and deeper overlap is owned by recovery, with no
     * unowned numerical interval between them.
     */
    public static final double CONTACT_SLOP = 1.0E-7D;

    /** Minimum meaningful penetration reported to initial-overlap recovery. */
    public static final double PENETRATION_EPSILON = CONTACT_SLOP;

    /** Movement below this magnitude is numerically zero. */
    public static final double ZERO_VECTOR_EPSILON = 1.0E-7D;
    public static final double ZERO_VECTOR_EPSILON_SQUARED =
            ZERO_VECTOR_EPSILON * ZERO_VECTOR_EPSILON;

    /** Equality band for normalized sweep time. */
    public static final double TOI_EPSILON = 1.0E-7D;

    /** Separation left before a TOI contact to avoid committing penetration. */
    public static final double CONTACT_SKIN = 1.0E-6D;

    /**
     * Gap below which a capsule conservative-advancement loop treats the body
     * as reached the contact boundary. This is numerically smaller than
     * {@link #CONTACT_SLOP} so a converged loop never leaves a meaningful
     * separation on the table.
     */
    public static final double CCD_CONVERGENCE_GAP = 1.0E-10D;

    /**
     * Same-constraint identity threshold. Only numerically-identical canonical
     * normals are merged before the projector; near-parallel planes are retained
     * so {@code ContactConstraintProjector}'s rank handling sees them as separate
     * (typically dependent) constraints.
     */
    public static final double SAME_CONSTRAINT_IDENTITY_DOT = 1.0D - 1.0E-12D;

    /** Named angular tolerance for collapsing coplanar/cotemporal voxel seams. */
    public static final double VOXEL_SEAM_NORMAL_DOT = 1.0D - 1.0E-7D;

    /** Short outward sample used only to identify an occluded static voxel face. */
    public static final double VOXEL_OCCLUSION_PROBE_DISTANCE = 4.0E-6D;

    /** A vector enters a plane only below the negative of this value. */
    public static final double ENTERING_PLANE_EPSILON = 1.0E-9D;

    /** Squared cross-product magnitude below which a crease is degenerate. */
    public static final double PARALLEL_PLANE_CROSS_SQUARED = 1.0E-14D;

    private CollisionTolerances() {}

    /**
     * Unified same-constraint identity test. Merges only when the two canonical
     * normals are numerically identical; unknown geometric feature identity
     * deliberately keeps the constraint.
     */
    /** Neutral-vector identity test; see {@link #sameConstraintIdentity}. */
    public static boolean sameConstraintIdentity(Vec3d first, Vec3d second) {
        return first.dot(second) >= SAME_CONSTRAINT_IDENTITY_DOT;
    }
}