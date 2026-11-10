package cc.sighs.gravityengine.attitude.runtime;

/** Whether the component can safely continue its retained displayed pose. */
public enum BodyAttitudeContinuity {
    PENDING_BOOTSTRAP,
    CONTINUOUS,
    INVALID
}
