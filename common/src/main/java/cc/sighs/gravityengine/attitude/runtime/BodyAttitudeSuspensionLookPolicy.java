package cc.sighs.gravityengine.attitude.runtime;

import java.util.Objects;

/** Look-reference ownership policy for an immediate Vanilla suspension. */
public enum BodyAttitudeSuspensionLookPolicy {
    /** Rebase the active world look into the vanilla/gravity scalar carrier. */
    PRESERVE_WORLD_LOOK,
    /** Vanilla immediately replaces the camera orientation itself. */
    VANILLA_FORCED_CAMERA;

    /**
     * Minecraft 1.21.1 {@code Camera.setup} forces a bed-facing camera only
     * for sleeping.  Other current suspension states continue to consume the
     * ordinary entity yaw/pitch scalars and therefore need a rebase.
     */
    public static BodyAttitudeSuspensionLookPolicy forReason(
            BodyAttitudeSuspensionReason reason
    ) {
        Objects.requireNonNull(reason, "reason");
        return reason == BodyAttitudeSuspensionReason.SLEEPING
                ? VANILLA_FORCED_CAMERA
                : PRESERVE_WORLD_LOOK;
    }
}
