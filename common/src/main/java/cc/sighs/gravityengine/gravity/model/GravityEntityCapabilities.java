package cc.sighs.gravityengine.gravity.model;

import java.util.Objects;
import java.util.Set;

/**
 * Resolved semantic classification of one entity kind.
 *
 * <p>This is the single entity-kind classification feeding the application
 * planner. It replaces the previous orthogonal motion/body/contact/
 * presentation capability axes and the legal-combination matrix they
 * required.</p>
 */
public enum GravityEntityCapabilities {
    /** Vanilla owns lifecycle and movement; no GravityEngine gravity behavior. */
    VANILLA_SPECIAL(Set.of(GravityAccelerationMode.NONE)),
    /** Direct ballistic integration with Vanilla AABB collision. */
    BALLISTIC(Set.of(GravityAccelerationMode.FIELD, GravityAccelerationMode.DIRECT)),
    /** Ballistic integration with a passive support solve. */
    PASSIVE_BALLISTIC(Set.of(GravityAccelerationMode.FIELD, GravityAccelerationMode.DIRECT)),
    /** Controlled flight that cannot consume the character kernel. */
    FLYING_BLOCKER(Set.of(GravityAccelerationMode.FIELD)),
    /** Ordinary ground/air character movement. */
    CHARACTER(Set.of(GravityAccelerationMode.FIELD, GravityAccelerationMode.DIRECT));

    private final Set<GravityAccelerationMode> accelerationModes;

    GravityEntityCapabilities(Set<GravityAccelerationMode> accelerationModes) {
        this.accelerationModes = Set.copyOf(accelerationModes);
    }

    public boolean supports(GravityAccelerationMode mode) {
        return accelerationModes.contains(Objects.requireNonNull(mode, "mode"));
    }
}
