package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of one authoritative gravity-field composition.
 *
 * <p>The authoritative acceleration is the world-space vector sum of all
 * contributions in the effective composition group selected by the
 * gravity-field composition service. Ordinary ADDITIVE fields compose
 * together; when one or more active OVERRIDE instances exist, only the
 * OVERRIDE group participates in the resultant.</p>
 *
 * <p>Contribution presence and resultant magnitude are separate facts.
 * Active contributions may sum to zero, and an individual active field may
 * itself contribute a zero vector.</p>
 */
public record GravitySample(
        Vec3 samplePoint,
        Vec3 accelerationVector,
        List<GravityContribution> contributions
) {
    public GravitySample {
        Objects.requireNonNull(samplePoint, "samplePoint");
        Objects.requireNonNull(accelerationVector, "accelerationVector");
        Objects.requireNonNull(contributions, "contributions");
        if (!Double.isFinite(samplePoint.x) || !Double.isFinite(samplePoint.y) || !Double.isFinite(samplePoint.z)) {
            throw new IllegalArgumentException("samplePoint components must be finite: " + samplePoint);
        }
        if (!Double.isFinite(accelerationVector.x) || !Double.isFinite(accelerationVector.y) || !Double.isFinite(accelerationVector.z)) {
            throw new IllegalArgumentException("accelerationVector must be finite: " + accelerationVector);
        }
        contributions = List.copyOf(contributions);
    }

    public static GravitySample zero(Vec3 samplePoint) {
        Objects.requireNonNull(samplePoint, "samplePoint");
        return new GravitySample(samplePoint, Vec3.ZERO, List.of());
    }

    /**
     * Creates a sample from a {@code GravityState} at the given point.
     */
    public static GravitySample fromState(GravityState state, Vec3 samplePoint) {
        Objects.requireNonNull(state, "state");
        Vec3 down = state.downAt(samplePoint);
        double strength = state.strength();
        Vec3 accel = down.scale(strength);
        return new GravitySample(samplePoint, accel, List.of());
    }

    /**
     * Creates a sample from a {@code GravityFrame} with source metadata from
     * the owning {@code GravityState}.
     */
    public static GravitySample fromFrame(GravityState state, GravityFrame frame) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(frame, "frame");
        Vec3 accel = frame.down().scale(frame.strength());
        return new GravitySample(frame.samplePoint(), accel, List.of());
    }

    /**
     * Creates a sample directly from a frame, without requiring a
     * companion state. Used for nested operations that borrow the
     * outer frame.
     */
    public static GravitySample fromFrame(GravityFrame frame) {
        Objects.requireNonNull(frame, "frame");
        Vec3 accel = frame.down().scale(frame.strength());
        return new GravitySample(frame.samplePoint(), accel, List.of());
    }

    public boolean hasAcceleration() {
        return accelerationVector.lengthSqr() > 0.0D;
    }

    /** Field presence is contribution identity, not resultant magnitude. */
    public boolean hasActiveField() {
        return !contributions.isEmpty();
    }

    public double accelerationMagnitude() {
        return accelerationVector.length();
    }

    public Optional<Vec3> down() {
        if (!hasAcceleration()) return Optional.empty();
        return Optional.of(accelerationVector.normalize());
    }

    public boolean isDefault() {
        return !hasAcceleration() && contributions.isEmpty();
    }
}
