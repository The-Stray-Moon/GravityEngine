package cc.sighs.gravityengine.attitude;

/**
 * One simulation step of attitude-control input.
 *
 * <p>Pitch/yaw are the look angular displacements consumed from the vanilla
 * {@code yRot}/{@code xRot} scalar stream during this interval (radians);
 * they are rate commands for this simulation step and are deliberately never
 * clamped to {@code [-1, 1]}.  Roll is the held-key axis {@code -1/0/+1}
 * semantic sampled from held key state, never from a key-repeat event.</p>
 *
 * <p>{@code rollAxis} feeds exactly one owner per mode: the FREE_ATTITUDE
 * geometric SemanticView controller advances the view orientation at the
 * configured controller roll rate, while ELYTRA_ALIGNED turns the same held
 * axis into a sustained world-space roll torque about the physical flight
 * forward axis. The two paths must not be active simultaneously.</p>
 */
public record BodyAttitudeInput(
        double pitchDeltaRadians,
        double yawDeltaRadians,
        double rollAxis
) {
    public static final BodyAttitudeInput NONE =
            new BodyAttitudeInput(0.0D, 0.0D, 0.0D);

    public BodyAttitudeInput {
        requireFinite(pitchDeltaRadians, "pitchDeltaRadians");
        requireFinite(yawDeltaRadians, "yawDeltaRadians");
        requireFinite(rollAxis, "rollAxis");
        rollAxis = Math.max(-1.0D, Math.min(1.0D, rollAxis));
    }

    public static BodyAttitudeInput rollOnly(double rollAxis) {
        return new BodyAttitudeInput(0.0D, 0.0D, rollAxis);
    }

    public boolean neutral() {
        return pitchDeltaRadians == 0.0D
                && yawDeltaRadians == 0.0D
                && rollAxis == 0.0D;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite"
            );
        }
    }
}
