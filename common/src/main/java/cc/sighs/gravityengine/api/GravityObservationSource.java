package cc.sighs.gravityengine.api;

/** Derived provenance of effective gravity; never persisted or used as readiness. */
public enum GravityObservationSource {
    /** Frozen evidence owned by the currently active movement operation. */
    ACTIVE_OPERATION,
    /** Evaluation from this tick whose physical and query context still matches. */
    CURRENT_TICK,
    /** No reusable evaluation: effective gravity is the assigned/bootstrap value. */
    ASSIGNMENT_FALLBACK
}
