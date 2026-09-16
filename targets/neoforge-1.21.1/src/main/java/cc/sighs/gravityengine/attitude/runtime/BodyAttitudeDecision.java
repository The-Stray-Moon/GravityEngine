package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.BodyAttitudeControlProfile;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Resolved attitude ownership for one tick.  An active decision carries the
 * resolved {@link BodyAttitudeControlProfile}; a suspended decision names the
 * temporary Vanilla-presentation exclusion reason.  There is no controller
 * mode on the decision.
 */
public record BodyAttitudeDecision(
        boolean active,
        @Nullable BodyAttitudeControlProfile profile,
        BodyAttitudeSuspensionReason suspensionReason
) {
    public BodyAttitudeDecision {
        Objects.requireNonNull(suspensionReason, "suspensionReason");
        if (active) {
            Objects.requireNonNull(profile, "active decision profile");
            if (suspensionReason != BodyAttitudeSuspensionReason.NONE) {
                throw new IllegalArgumentException(
                        "active decision cannot have a suspension reason");
            }
        } else {
            if (profile != null) {
                throw new IllegalArgumentException(
                        "suspended decision cannot select a control profile");
            }
            if (suspensionReason == BodyAttitudeSuspensionReason.NONE) {
                throw new IllegalArgumentException(
                        "suspended decision requires a reason");
            }
        }
    }

    public static BodyAttitudeDecision active(
            BodyAttitudeControlProfile profile
    ) {
        return new BodyAttitudeDecision(
                true,
                Objects.requireNonNull(profile, "profile"),
                BodyAttitudeSuspensionReason.NONE
        );
    }

    /** Held roll belongs to the free controller; Elytra retains its alignment actuator. */
    public boolean controllerRoll() {
        return active && profile.constraint() != cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind.ELYTRA_ALIGNED;
    }

    public static BodyAttitudeDecision suspended(
            BodyAttitudeSuspensionReason reason
    ) {
        return new BodyAttitudeDecision(false, null, reason);
    }

    /** Wire ownership carries no controller profile. This inert profile never simulates a remote actor. */
    public static BodyAttitudeDecision replicatedActive() {
        return active(new BodyAttitudeControlProfile(
                cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind.FREE_ATTITUDE, 0,
                cc.sighs.gravityengine.attitude.BodyAttitudeControlAuthority.none()));
    }
}
