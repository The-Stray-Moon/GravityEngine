package cc.sighs.gravityengine.math.geometry;

/** Numerical comparison bands used only by the Euclidean geometry kernel. */
public final class GeometryTolerance {
    /** Squared length at or below which a cross-product axis is degenerate. */
    public static final double DEGENERATE_AXIS_LENGTH_SQUARED = 1.0E-12D;

    /** Unit-length and pairwise-orthogonality validation tolerance. */
    public static final double ORTHONORMAL = 1.0E-10D;

    /** Signed separation/overlap band classified as touching. */
    public static final double TOUCHING = 1.0E-7D;

    /** Stable comparison band for normal orientation and overlap ties. */
    public static final double COMPARISON = 1.0E-12D;

    /** Relative displacement below which an interval is stationary on an axis. */
    public static final double PARALLEL = 1.0E-10D;

    /** Normalized-time equality band for sweep events. */
    public static final double TIME = 1.0E-7D;

    /** Signed motion band used to distinguish entering from tangent motion. */
    public static final double ENTERING = 1.0E-9D;

    /** Same-facing normal identity threshold for cotemporal feature merging. */
    public static final double SAME_NORMAL_DOT = 1.0D - 1.0E-12D;

    private GeometryTolerance() {}
}
