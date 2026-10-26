package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.gravity.kinematic.geometry.KinematicPose;

import java.util.Objects;

/**
 * Outcome of one PRESERVE_SUPPORT eligibility decision.
 *
 * <p>A rejected candidate carries a stable {@code fallbackReason} for runtime
 * debug (for example {@code FRAME_DELTA_TOO_LARGE} or
 * {@code CORRECTION_TOO_LARGE}); the caller falls back to the ordinary
 * legality/deferred path and never clamps a rejected correction.</p>
 */
public record SupportPreserveOutcome(
        KinematicPose pose,
        String fallbackReason
) {
    public SupportPreserveOutcome {
        if (pose == null) {
            Objects.requireNonNull(fallbackReason, "fallbackReason");
        } else {
            Objects.requireNonNull(pose, "pose");
            if (fallbackReason != null) {
                throw new IllegalArgumentException(
                        "accepted support-preserve outcome has a "
                                + "fallback reason");
            }
        }
    }

    public static SupportPreserveOutcome accepted(KinematicPose pose) {
        return new SupportPreserveOutcome(pose, null);
    }

    public static SupportPreserveOutcome rejected(String fallbackReason) {
        return new SupportPreserveOutcome(null, fallbackReason);
    }

    public boolean preserved() {
        return this.pose != null;
    }
}
