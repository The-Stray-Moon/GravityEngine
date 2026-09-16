package cc.sighs.gravityengine.attitude;

/**
 * Per-axis continuous authority for the player's attitude thrusters.
 *
 * <p>Every axis is a value in {@code [0, 1]}.  {@code 1} means the player
 * fully owns that rotation degree of freedom; {@code 0} means no player
 * thruster applies on that axis. Ordinary (non-Elytra) ACTIVE players receive
 * {@link #full()} authority: the authority is deliberately independent of
 * gravity strength, support weight and idle state, because environmental
 * gravity no longer owns any ordinary rotation degree of freedom. The Elytra
 * special owner still keeps pitch/yaw passive and grants roll only.</p>
 */
public record BodyAttitudeControlAuthority(
        double pitch,
        double yaw,
        double roll
) {
    /** Numerical activation epsilon used by ownership policy. */
    public static final double EPSILON = 1.0E-6D;

    public static final BodyAttitudeControlAuthority NONE = none();

    public BodyAttitudeControlAuthority {
        pitch = unit(pitch);
        yaw = unit(yaw);
        roll = unit(roll);
    }

    public double maximum() {
        return Math.max(pitch, Math.max(yaw, roll));
    }

    public static BodyAttitudeControlAuthority none() {
        return new BodyAttitudeControlAuthority(0.0D, 0.0D, 0.0D);
    }

    /** Full per-axis controller input authority for ordinary ACTIVE players. */
    public static BodyAttitudeControlAuthority full() {
        return new BodyAttitudeControlAuthority(1.0D, 1.0D, 1.0D);
    }

    public boolean anyAuthority() {
        return maximum() > EPSILON;
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "control authority must be finite: " + value);
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
