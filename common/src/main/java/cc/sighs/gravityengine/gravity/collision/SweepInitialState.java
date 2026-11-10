package cc.sighs.gravityengine.gravity.collision;

/** Exact narrow-phase classification of a sweep caller's body at t=0. */
public enum SweepInitialState {
    SEPARATED,
    TOUCHING,
    OVERLAPPING
}
