package cc.sighs.gravityengine.attitude;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.Objects;

/** Immutable, transient attitude state carried between fixed simulation steps. */
public final class BodyAttitudeState {
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-24D;

    private final Quaterniond previousWorldFromBody;
    private final Quaterniond currentWorldFromBody;
    private final Vec3 angularVelocityWorld;
    private final long tick;
    private final long revision;
    private final boolean initialized;

    public BodyAttitudeState(
            Quaterniond previousWorldFromBody,
            Quaterniond currentWorldFromBody,
            Vec3 angularVelocityWorld,
            long tick,
            long revision,
            boolean initialized
    ) {
        this.previousWorldFromBody = normalizedCopy(previousWorldFromBody,
                "previousWorldFromBody");
        this.currentWorldFromBody = normalizedCopy(currentWorldFromBody,
                "currentWorldFromBody");
        this.angularVelocityWorld = requireFinite(angularVelocityWorld, "angularVelocityWorld");
        if (tick < 0L) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.tick = tick;
        this.revision = revision;
        this.initialized = initialized;
    }

    public static BodyAttitudeState initialized(
            Quaterniond worldFromBody,
            Vec3 angularVelocityWorld,
            long tick,
            long revision
    ) {
        return new BodyAttitudeState(
                worldFromBody, worldFromBody, angularVelocityWorld, tick, revision, true
        );
    }

    public static BodyAttitudeState uninitialized() {
        Quaterniond identity = new Quaterniond();
        return new BodyAttitudeState(
                identity, identity, Vec3.ZERO,
                0L, 0L, false
        );
    }

    /**
     * Restores one durable disk seed into a fresh local state.
     *
     * <p>Old disk tick/revision values never survive reload:
     * {@code previousWorldFromBody == currentWorldFromBody} is intentional
     * (no pre-load interpolation baseline exists) and tick/revision restart
     * at zero so the fresh authoritative stream owns chronology.</p>
     */
    public static BodyAttitudeState fromPersisted(
            Quaterniond worldFromBody,
            Vec3 angularVelocityWorld) {
        return new BodyAttitudeState(
                worldFromBody,
                worldFromBody,
                angularVelocityWorld,
                0L,
                0L,
                true
        );
    }

    /** Tick history copy only: preserve current physics bit-for-bit, including on a rejected step. */
    public BodyAttitudeState withCurrentAsPrevious() {
        return previousWorldFromBody.equals(currentWorldFromBody) ? this : new BodyAttitudeState(this);
    }

    private BodyAttitudeState(BodyAttitudeState source) {
        // These private values are immutable in this class; public quaternion accessors copy them.
        previousWorldFromBody = source.currentWorldFromBody;
        currentWorldFromBody = source.currentWorldFromBody;
        angularVelocityWorld = source.angularVelocityWorld;
        tick = source.tick;
        revision = source.revision;
        initialized = source.initialized;
    }

    /** Returns a defensive mutable copy. */
    public Quaterniond previousWorldFromBody() {
        return new Quaterniond(this.previousWorldFromBody);
    }

    /** Returns a defensive mutable copy. */
    public Quaterniond currentWorldFromBody() {
        return new Quaterniond(this.currentWorldFromBody);
    }

    public Vec3 angularVelocityWorld() { return this.angularVelocityWorld; }
    /** Returns a defensive mutable copy. */

    public long tick() { return this.tick; }
    public long revision() { return this.revision; }
    public boolean initialized() { return this.initialized; }

    private static Quaterniond normalizedCopy(Quaterniond value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y)
                || !Double.isFinite(value.z) || !Double.isFinite(value.w)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(name + " must be non-degenerate");
        }
        return new Quaterniond(value).normalize();
    }

    private static Vec3 requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        if (!Double.isFinite(value.lengthSqr())) {
            throw new IllegalArgumentException(name + " magnitude must be finite: " + value);
        }
        return value;
    }
}
