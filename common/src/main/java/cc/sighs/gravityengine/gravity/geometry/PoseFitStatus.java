package cc.sighs.gravityengine.gravity.geometry;

/**
 * Whether one candidate body's exact occupancy could be proven legal against
 * the operation's frozen scene.
 *
 * <p>{@link #INDETERMINATE} is a real third answer, never a soft "probably
 * legal": a candidate that leaves the captured envelope or exhausts the shared
 * work budget cannot be installed or manufacture support. Rejecting a handoff
 * does not itself cancel movement, but an exhausted hard-collision budget
 * remains exhausted for the downstream movement owner; this boundary never
 * resets it.</p>
 */
public enum PoseFitStatus {
    /** The exact body has no meaningful penetration in the frozen scene. */
    LEGAL,
    /** The exact body really penetrates a captured obstacle. */
    ILLEGAL,
    /**
     * The frozen scene could not answer: the candidate left the captured
     * envelope ({@code SCENE_COVERAGE}) or the shared work budget was
     * exhausted ({@code WORK_BUDGET}).
     */
    INDETERMINATE,
    /** The layer was never reached (deadband, authority gate, earlier rejection). */
    NOT_ATTEMPTED
}
