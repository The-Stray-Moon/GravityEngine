package cc.sighs.gravityengine.gravity.geometry;

/**
 * The explicit geometric-pose handoff decision for one outermost character
 * movement operation.
 *
 * <p>A transition kind names which candidate anchor policy owns the frame
 * change. It is deliberately not derivable from "the geometry changed": a
 * {@link #DIRECT_AT_ANCHOR} replacement and a {@link #PRESERVE_SUPPORT}
 * re-anchor both rebuild the exact body, but only the latter has revalidated a
 * real support plane and may carry the previous completed endpoint forward.</p>
 */
public enum GeometryTransitionKind {

    /**
     * Collision-axis deadband: the installed occupancy is worth keeping and
     * only the environmental evidence is refreshed.
     */
    UNCHANGED,

    /**
     * The exact body at the authoritative position anchor under the proposed
     * axis is legal without any support-plane correction.
     */
    DIRECT_AT_ANCHOR,

    /**
     * The proposed axis preserves the previous trusted single-face support
     * plane through a validated bounded correction (which may be zero).
     */
    PRESERVE_SUPPORT,

    /** No legal candidate exists; the installed representation is retained. */
    DEFERRED
}
