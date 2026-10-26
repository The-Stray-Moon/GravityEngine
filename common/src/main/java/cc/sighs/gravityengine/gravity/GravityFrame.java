package cc.sighs.gravityengine.gravity;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;

import java.util.Objects;

/**
 * Immutable snapshot of one gravity reference frame.
 *
 * <p>Canonical local axes:
 * down=(0,-1,0), up=(0,1,0), left=(1,0,0), forward=(0,0,1). The orientation
 * is stored exactly once as an {@link OrthonormalFrame3d} whose X/Y/Z axes
 * are the canonical left/up/forward axes. All convenience accessors are
 * derived from that single frame.</p>
 */
public record GravityFrame(
        Vec3d samplePoint,
        OrthonormalFrame3d orientation,
        double strength
) {
    private static final double EPSILON = 1.0E-7D;

    /**
     * Exact-parallel band for canonical tangent completion. Only vectors
     * whose dot product is inside this band are treated as exactly
     * parallel/antiparallel; every small but real direction change completes
     * through the double-precision rotation so the stored orientation always
     * satisfies {@code up() == -down()} exactly.
     */
    private static final double PARALLEL_EPSILON = 1.0E-12D;

    /**
     * Resultants at or below 0.5% of vanilla gravity have no trustworthy
     * orientation. Their magnitude remains physical, but an existing frame
     * keeps its completed direction and tangent basis.
     */
    public static final double DIRECTION_DETACH_STRENGTH =
            GravityState.VANILLA_STRENGTH * 0.005D;

    /** A live resultant must reach 1% of vanilla gravity before it owns direction. */
    public static final double DIRECTION_ATTACH_STRENGTH =
            GravityState.VANILLA_STRENGTH * 0.01D;

    public static final GravityFrame DEFAULT = create(
            Vec3d.ZERO,
            GravityState.DEFAULT_DOWN,
            GravityState.VANILLA_STRENGTH
    );

    public static GravityFrame fromState(
            GravityState state,
            Vec3d samplePoint
    ) {
        return create(samplePoint, state.downAt(samplePoint), state.strength());
    }

    /**
     * Resolves current environmental evidence while preserving the previous
     * tangent gauge through parallel transport.
     */
    public static GravityFrame fromState(
            GravityState state,
            Vec3d samplePoint,
            GravityFrame previousFrame
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(samplePoint, "samplePoint");
        return create(
                samplePoint,
                state.downAt(samplePoint),
                state.strength(),
                previousFrame
        );
    }

    public static GravityFrame fromEnvironmentalEvidence(
            Vec3d samplePoint,
            Vec3d acceleration,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        double strength = acceleration.length();

        /*
         * Force magnitude and reference-orientation confidence are separate.
         * A tiny non-zero cancellation remainder remains the exact published
         * strength, but cannot be normalized into a tick-to-tick 180-degree
         * frame flip. The band between detach and attach deliberately keeps
         * the completed direction, giving the stateless policy a deterministic
         * deadband without adding history to GravityState.
         */
        if (usesLiveDirection(strength)) {
            return create(
                    samplePoint,
                    acceleration.multiply(1.0D / strength),
                    strength,
                    previousFrame
            );
        }

        Vec3d fallback;
        if (previousFrame != null && isUsable(previousFrame.down())) {
            fallback = previousFrame.down();
        } else if (isUsable(fallbackDown)) {
            fallback = fallbackDown;
        } else {
            fallback = GravityState.DEFAULT_DOWN;
        }
        return create(
                samplePoint,
                fallback,
                Double.isFinite(strength) ? strength : 0.0D,
                previousFrame
        );
    }

    public static GravityFrame fromAcceleration(
            Vec3d samplePoint,
            Vec3d acceleration,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        if (isUsable(acceleration)) {
            return create(
                    samplePoint,
                    acceleration.normalized(),
                    acceleration.length(),
                    previousFrame
            );
        }
        Vec3d fallback = isUsable(fallbackDown)
                ? fallbackDown : GravityState.DEFAULT_DOWN;
        return create(samplePoint, fallback, 0.0D, previousFrame);
    }

    public static GravityFrame fromDown(Vec3d down, double strength) {
        return create(Vec3d.ZERO, down, strength);
    }

    /**
     * Fresh environmental evidence completed on an already installed collision
     * up axis, without changing physical occupancy.
     *
     * <p>When the evidence already carries that axis the exact evidence value
     * is returned; otherwise the completed frame keeps the installed up axis
     * and the evidence's tangent gauge.</p>
     */
    public static GravityFrame completedOnUpAxis(
            Vec3d installedUp,
            GravityFrame evidence
    ) {
        Objects.requireNonNull(installedUp, "installedUp");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.up().distanceSquared(installedUp) <= 1e-28) {
            return evidence;
        }
        GravityFrame aligned = fromAcceleration(
                evidence.samplePoint(),
                installedUp.negate(),
                installedUp.negate(),
                evidence
        );
        OrthonormalFrame3d basis = new OrthonormalFrame3d(
                aligned.orientation().axisX(),
                new Vec3d(
                        installedUp.x(),
                        installedUp.y(),
                        installedUp.z()
                ),
                aligned.orientation().axisZ()
        );
        return new GravityFrame(
                evidence.samplePoint(),
                basis,
                evidence.strength()
        );
    }

    public static GravityFrame downOnly(Vec3d down) {
        return fromDown(down, GravityState.VANILLA_STRENGTH);
    }

    public boolean isDefault() {
        return down().distanceSquared(GravityState.DEFAULT_DOWN)
                <= EPSILON * EPSILON
                && Double.compare(strength, GravityState.VANILLA_STRENGTH) == 0;
    }

    /** Canonical gravity-local +X world axis (body/gravity left). */
    public Vec3d left() {
        return orientation.axisX();
    }

    /** Canonical gravity-local +Y world axis (up, opposite of down). */
    public Vec3d up() {
        return orientation.axisY();
    }

    /** Canonical gravity-local +Z world axis (forward). */
    public Vec3d forward() {
        return orientation.axisZ();
    }

    /** Negated canonical +Y axis (gravity direction). */
    public Vec3d down() {
        return orientation.axisY().negate();
    }

    /**
     * Canonical gravity-local transform: projects a world vector onto the
     * frame's left/up/forward axes.
     */
    public Vec3d worldToLocal(Vec3d world) {
        return orientation.worldToLocal(world);
    }

    /**
     * Canonical gravity-local transform: rebuilds a world vector from
     * left/up/forward components.
     */
    public Vec3d localToWorld(Vec3d local) {
        return orientation.localToWorld(local);
    }

    private static GravityFrame create(
            Vec3d samplePoint,
            Vec3d requestedDown,
            double strength
    ) {
        return create(samplePoint, requestedDown, strength, null);
    }

    private static GravityFrame create(
            Vec3d samplePoint,
            Vec3d requestedDown,
            double strength,
            GravityFrame previousFrame
    ) {
        Vec3d down = normalizeDown(requestedDown);
        /*
         * The exact normalized gravity direction is the authoritative -Y
         * axis of the canonical orientation. Completing the tangent from a
         * float-snapped rotation would let up() drift away from -down() for
         * small live direction changes.
         */
        Vec3d up = down.negate();
        Vec3d forward = transportedForward(up, previousFrame);
        Vec3d left;

        if (forward == null) {
            Vec3d forwardSeed = canonicalForward(down);
            left = up.cross(forwardSeed).normalized();
            forward = left.cross(up).normalized();
        }

        left = up.cross(forward).normalized();
        forward = left.cross(up).normalized();
        return new GravityFrame(
                samplePoint,
                orientationFromAxes(left, up, forward),
                strength
        );
    }

    private static OrthonormalFrame3d orientationFromAxes(
            Vec3d left,
            Vec3d up,
            Vec3d forward
    ) {
        return new OrthonormalFrame3d(
                left.x(), left.y(), left.z(),
                up.x(), up.y(), up.z(),
                forward.x(), forward.y(), forward.z()
        );
    }

    private static Vec3d transportedForward(
            Vec3d newUp,
            GravityFrame previousFrame
    ) {
        if (previousFrame == null) return null;
        Vec3d projected = projectTangent(previousFrame.forward(), newUp);
        if (projected != null) return projected;
        return projectTangent(previousFrame.left().negate(), newUp);
    }

    private static Vec3d projectTangent(Vec3d vector, Vec3d newUp) {
        if (!isUsable(vector)) return null;
        Vec3d projected = vector.subtract(
                newUp.multiply(vector.dot(newUp)));
        return isUsable(projected) ? projected.normalized() : null;
    }

    /**
     * Deterministic canonical tangent seed for a frame with no previous
     * tangent continuity. Exact parallel returns canonical +Z; exact
     * antiparallel uses the same +Z-preserving 180-degree convention; every
     * other direction rotates +Z through the double-precision rotation that
     * maps canonical down to the requested down.
     */
    private static Vec3d canonicalForward(Vec3d normalizedDown) {
        Vec3d localDown = new Vec3d(0.0D, -1.0D, 0.0D);
        double dot = localDown.dot(normalizedDown);
        if (dot >= 1.0D - PARALLEL_EPSILON
                || dot <= -1.0D + PARALLEL_EPSILON) {
            return Vec3d.Z;
        }
        Quatd rotation = Quatd.rotationTo(localDown, normalizedDown);
        return rotation.transform(Vec3d.Z);
    }

    private static Vec3d normalizeDown(Vec3d down) {
        if (!isUsable(down)) {
            return GravityState.DEFAULT_DOWN;
        }
        return down.normalized();
    }

    private static boolean isUsable(Vec3d vector) {
        return vector != null
                && vector.isFinite()
                && vector.lengthSquared() >= EPSILON;
    }

    private static boolean usesLiveDirection(double strength) {
        if (!Double.isFinite(strength)
                || strength <= DIRECTION_DETACH_STRENGTH) {
            return false;
        }
        if (strength >= DIRECTION_ATTACH_STRENGTH) {
            return true;
        }
        // Confidence transition band: retain the completed direction.
        return false;
    }
}
