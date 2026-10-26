package cc.sighs.gravityengine.gravity.geometry;

/** Terminal outcome class of one geometry transition commit. */
public enum GeometryTransitionStatus {
    UNCHANGED,
    APPLIED,
    RECOVERED,
    DEFERRED,
    FAILED
}
