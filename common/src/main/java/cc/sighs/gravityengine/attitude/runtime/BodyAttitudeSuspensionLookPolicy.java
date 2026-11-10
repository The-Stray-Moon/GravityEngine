package cc.sighs.gravityengine.attitude.runtime;

/**
 * Look-reference ownership policy while body-attitude control
 * is suspended.
 */
public enum BodyAttitudeSuspensionLookPolicy {
    /** Preserve/rebase the current world-space look reference. */
    PRESERVE_WORLD_LOOK,

    /**
     * The target presentation system immediately replaces the
     * camera/view orientation itself.
     */
    VANILLA_FORCED_CAMERA
}