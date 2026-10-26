package cc.sighs.gravityengine.gravity;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Persistent resultant gravity state.
 *
 * <p>The state is always canonical:
 * direction is finite, non-degenerate and normalized;
 * strength is finite.</p>
 */
public final class GravityState {
    public static final double VANILLA_STRENGTH = 0.08D;
    public static final Vec3d DEFAULT_DOWN =
            new Vec3d(0.0D, -1.0D, 0.0D);

    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0E-7D;

    public static final GravityState DEFAULT =
            new GravityState(DEFAULT_DOWN, VANILLA_STRENGTH);

    private final Vec3d down;
    private final double strength;

    public GravityState(Vec3d down, double strength) {
        Objects.requireNonNull(down, "down");

        if (!down.isFinite()) {
            throw new IllegalArgumentException(
                    "gravity direction must be finite: " + down
            );
        }

        double lengthSquared = down.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared < MIN_DIRECTION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "gravity direction must be non-degenerate: " + down
            );
        }

        if (!Double.isFinite(strength)) {
            throw new IllegalArgumentException(
                    "gravity strength must be finite: " + strength
            );
        }

        this.down = down.multiply(1.0D / Math.sqrt(lengthSquared));
        this.strength = strength;
    }

    public Vec3d down() {
        return this.down;
    }

    public double strength() {
        return this.strength;
    }

    public boolean sameSyncData(GravityState other) {
        Objects.requireNonNull(other, "other");
        return sameDirection(other)
                && Double.compare(this.strength, other.strength) == 0;
    }

    public boolean isDefault() {
        return this.sameDirection(DEFAULT)
                && Double.compare(this.strength, VANILLA_STRENGTH) == 0;
    }

    public boolean sameDirection(GravityState other) {
        Objects.requireNonNull(other, "other");
        return this.down.distanceSquared(other.down) < 1.0E-8D;
    }

    /**
     * Canonical value equality.
     *
     * <p>{@code CommittedGravityApplication} is a record whose equality decides
     * whether an authoritative body application actually changed, and the body
     * commit codec round-trips this canonical down/strength pair. Reference
     * equality would make every decoded application look like a change, so the
     * canonical value is the identity here.</p>
     *
     * <p>This is exact. {@link #sameDirection} keeps its separate tolerance for
     * gameplay direction comparison.</p>
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GravityState state)) return false;
        return Double.compare(this.strength, state.strength) == 0
                && Double.compare(this.down.x(), state.down.x()) == 0
                && Double.compare(this.down.y(), state.down.y()) == 0
                && Double.compare(this.down.z(), state.down.z()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.down.x(),
                this.down.y(),
                this.down.z(),
                this.strength
        );
    }

    /**
     * Resultant states store a world-space direction directly.
     * Radial/source-dependent evaluation belongs to GravityFieldService.
     */
    public Vec3d downAt(Vec3d samplePoint) {
        Objects.requireNonNull(samplePoint, "samplePoint");
        return this.down;
    }
}
