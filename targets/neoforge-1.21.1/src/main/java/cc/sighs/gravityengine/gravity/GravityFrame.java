package cc.sighs.gravityengine.gravity;

import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

import java.lang.Math;

/**
 * Immutable snapshot of one gravity reference frame.
 *
 * Canonical local axes:
 * down=(0,-1,0), up=(0,1,0), left=(1,0,0), forward=(0,0,1).
 *
 * <p>The orientation is stored exactly once as an
 * {@link OrthonormalFrame3d} whose X/Y/Z axes are the canonical
 * left/up/forward axes.  The Minecraft {@code Vec3} convenience accessors
 * ({@link #down()}, {@link #up()}, ...) are all derived from that single
 * frame; no second independent basis is stored.  Environment metadata
 * (sample point, strength,
 * direction-confidence band) remains part of this domain snapshot and is not
 * merged into body-attitude state.</p>
 */
public record GravityFrame(
        Vec3 samplePoint,
        OrthonormalFrame3d orientation,
        double strength
) {
    private static final double EPSILON = 1.0E-7D;

    /**
     * Exact-parallel band for canonical tangent completion.  Only vectors
     * whose dot product is inside this band are treated as exactly
     * parallel/antiparallel; every small but real direction change completes
     * through the double-precision rotation so the stored orientation always
     * satisfies {@code up() == -down()} exactly.
     */
    private static final double PARALLEL_EPSILON = 1.0E-12D;

    /**
     * Resultants at or below 0.5% of vanilla gravity have no trustworthy
     * orientation.  Their magnitude remains physical, but an existing frame
     * keeps its completed direction and tangent basis.
     */
    public static final double DIRECTION_DETACH_STRENGTH =
            GravityState.VANILLA_STRENGTH * 0.005D;

    /** A live resultant must reach 1% of vanilla gravity before it owns direction. */
    public static final double DIRECTION_ATTACH_STRENGTH =
            GravityState.VANILLA_STRENGTH * 0.01D;

    public static final GravityFrame DEFAULT = create(
            Vec3.ZERO,
            GravityState.DEFAULT_DOWN,
            GravityState.VANILLA_STRENGTH
    );

    public static GravityFrame fromState(GravityState state, Vec3 samplePoint) {
        return create(samplePoint, state.downAt(samplePoint), state.strength());
    }

    public static GravityFrame fromEnvironmentalEvidence(
            Vec3 samplePoint,
            Vec3 acceleration,
            Vec3 fallbackDown,
            GravityFrame previousFrame
    ) {
        double strength = acceleration.length();

        /*
         * Force magnitude and reference-orientation confidence are separate.
         * A tiny non-zero cancellation remainder remains the exact published
         * strength, but cannot be normalized into a tick-to-tick 180-degree
         * frame flip.  The band between detach and attach deliberately keeps
         * the completed direction, giving the stateless policy a deterministic
         * deadband without adding history to GravityState.
         */
        if (usesLiveDirection(strength)) {
            return create(
                    samplePoint,
                    acceleration.scale(1.0D / strength),
                    strength,
                    previousFrame
            );
        }

        Vec3 fallback;
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
            Vec3 samplePoint,
            Vec3 acceleration,
            Vec3 fallbackDown,
            GravityFrame previousFrame
    ) {
        if (isUsable(acceleration)) {
            return create(
                    samplePoint,
                    acceleration.normalize(),
                    acceleration.length(),
                    previousFrame
            );
        }
        Vec3 fallback = isUsable(fallbackDown)
                ? fallbackDown : GravityState.DEFAULT_DOWN;
        return create(samplePoint, fallback, 0.0D, previousFrame);
    }

    public static GravityFrame fromDown(Vec3 down, double strength) {
        return create(Vec3.ZERO, down, strength);
    }

    public static GravityFrame downOnly(Vec3 down) {
        return fromDown(down, GravityState.VANILLA_STRENGTH);
    }

    public boolean isDefault() {
        return down().distanceToSqr(GravityState.DEFAULT_DOWN)
                <= EPSILON * EPSILON
                && Double.compare(strength, GravityState.VANILLA_STRENGTH) == 0;
    }

    /** Canonical gravity-local +X world axis (body/gravity left). */
    public Vec3 left() {
        return axisToMinecraft(orientation.axisX(new Vector3d()));
    }

    /** Canonical gravity-local +Y world axis (up, opposite of down). */
    public Vec3 up() {
        return axisToMinecraft(orientation.axisY(new Vector3d()));
    }

    /** Canonical gravity-local +Z world axis (forward). */
    public Vec3 forward() {
        return axisToMinecraft(orientation.axisZ(new Vector3d()));
    }

    /** Negated canonical +Y axis (gravity direction). */
    public Vec3 down() {
        Vector3d axisY = orientation.axisY(new Vector3d());
        return new Vec3(-axisY.x, -axisY.y, -axisY.z);
    }

    /**
     * Canonical gravity-local transform: projects a world vector onto the
     * frame's left/up/forward axes.  Delegates to the pure
     * {@link OrthonormalFrame3d} math authority.
     */
    public Vec3 worldToLocal(Vec3 world) {
        Vector3d local = new Vector3d(world.x, world.y, world.z);
        orientation.worldToLocal(local, local);
        return new Vec3(local.x, local.y, local.z);
    }

    /**
     * Canonical gravity-local transform: rebuilds a world vector from
     * left/up/forward components.  Delegates to the pure
     * {@link OrthonormalFrame3d} math authority.
     */
    public Vec3 localToWorld(Vec3 local) {
        Vector3d world = new Vector3d(local.x, local.y, local.z);
        orientation.localToWorld(world, world);
        return new Vec3(world.x, world.y, world.z);
    }

    /** Returns a new mutable quaternion representing this immutable frame. */
    public Quaternionf rotation() {
        return basisToQuaternion(
                orientation.axisX(new Vector3d()),
                orientation.axisY(new Vector3d()),
                orientation.axisZ(new Vector3d())
        );
    }

    /**
     * Interpolates render orientation along the shortest quaternion arc.
     * Physics always consumes completed endpoint frames; this method is only
     * for presentation snapshots between those endpoints.
     */
    public static GravityFrame interpolateForPresentation(
            GravityFrame previous,
            GravityFrame current,
            float progress
    ) {
        if (previous == null) return current;
        if (current == null) return previous;

        float t = Math.max(0.0F, Math.min(1.0F, progress));
        if (t == 0.0F) return previous;
        if (t == 1.0F) return current;

        Quaternionf rotation = previous.rotation()
                .slerp(current.rotation(), t)
                .normalize();
        Vec3 samplePoint = previous.samplePoint().lerp(current.samplePoint(), t);
        double strength = previous.strength
                + (current.strength - previous.strength) * t;
        return fromRotation(samplePoint, rotation, strength);
    }

    private static Quaternionf basisToQuaternion(
            Vector3d axisX,
            Vector3d axisY,
            Vector3d axisZ
    ) {
        Matrix3f matrix = new Matrix3f();
        matrix.setColumn(
                0,
                new Vector3f((float) axisX.x, (float) axisX.y, (float) axisX.z)
        );
        matrix.setColumn(
                1,
                new Vector3f((float) axisY.x, (float) axisY.y, (float) axisY.z)
        );
        matrix.setColumn(
                2,
                new Vector3f((float) axisZ.x, (float) axisZ.y, (float) axisZ.z)
        );
        return matrix.getNormalizedRotation(new Quaternionf()).normalize();
    }

    private static GravityFrame fromRotation(
            Vec3 samplePoint,
            Quaternionf rotation,
            double strength
    ) {
        Vec3 up = rotate(rotation, new Vector3f(0.0F, 1.0F, 0.0F));
        Vec3 left = rotate(rotation, new Vector3f(1.0F, 0.0F, 0.0F));
        Vec3 forward = rotate(rotation, new Vector3f(0.0F, 0.0F, 1.0F));

        // Re-orthogonalize once after float quaternion interpolation so the
        // frame keeps the same handedness and tolerance guarantees as create.
        left = up.cross(forward).normalize();
        forward = left.cross(up).normalize();
        return new GravityFrame(
                samplePoint,
                orientationFromAxes(left, up, forward),
                strength
        );
    }

    /** Derives a composed-field sample from the actual frame acceleration. */


    private static GravityFrame create(
            Vec3 samplePoint,
            Vec3 requestedDown,
            double strength
    ) {
        return create(samplePoint, requestedDown, strength, null);
    }

    private static GravityFrame create(
            Vec3 samplePoint,
            Vec3 requestedDown,
            double strength,
            GravityFrame previousFrame
    ) {
        Vec3 down = normalizeDown(requestedDown);
        // The exact normalized gravity direction is the authoritative -Y
        // axis of the canonical orientation.  Completing the tangent from a
        // float-snapped rotation would let up() drift away from -down() for
        // small live direction changes, which in turn would erase the
        // collision-frame angular deadband decisions.
        Vec3 up = down.reverse();
        Vec3 forward = transportedForward(up, previousFrame);
        Vec3 left;

        if (forward == null) {
            Vec3 forwardSeed = canonicalForward(down);
            left = up.cross(forwardSeed).normalize();
            forward = left.cross(up).normalize();
        }

        left = up.cross(forward).normalize();
        forward = left.cross(up).normalize();
        return new GravityFrame(
                samplePoint,
                orientationFromAxes(left, up, forward),
                strength
        );
    }

    private static OrthonormalFrame3d orientationFromAxes(
            Vec3 left,
            Vec3 up,
            Vec3 forward
    ) {
        return new OrthonormalFrame3d(
                left.x, left.y, left.z,
                up.x, up.y, up.z,
                forward.x, forward.y, forward.z
        );
    }

    private static Vec3 transportedForward(
            Vec3 newUp,
            GravityFrame previousFrame
    ) {
        if (previousFrame == null) return null;
        Vec3 projected = projectTangent(previousFrame.forward(), newUp);
        if (projected != null) return projected;
        return projectTangent(previousFrame.left().reverse(), newUp);
    }

    private static Vec3 projectTangent(Vec3 vector, Vec3 newUp) {
        if (!isUsable(vector)) return null;
        Vec3 projected = vector.subtract(newUp.scale(vector.dot(newUp)));
        return isUsable(projected) ? projected.normalize() : null;
    }

    /**
     * Deterministic canonical tangent seed for a frame with no previous
     * tangent continuity.  Exact parallel returns canonical +Z; exact
     * antiparallel uses the same +Z-preserving 180-degree convention; every
     * other direction rotates +Z through the double-precision rotation that
     * maps canonical down to the requested down.
     */
    private static Vec3 canonicalForward(Vec3 normalizedDown) {
        Vector3d localDown = new Vector3d(0.0D, -1.0D, 0.0D);
        Vector3d worldDown = new Vector3d(
                normalizedDown.x,
                normalizedDown.y,
                normalizedDown.z
        );
        double dot = localDown.dot(worldDown);
        if (dot >= 1.0D - PARALLEL_EPSILON
                || dot <= -1.0D + PARALLEL_EPSILON) {
            return new Vec3(0.0D, 0.0D, 1.0D);
        }
        Quaterniond rotation = localDown.rotationTo(
                worldDown, new Quaterniond()
        ).normalize();
        Vector3d rotated = rotation.transform(
                new Vector3d(0.0D, 0.0D, 1.0D)
        );
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    private static Vec3 rotate(Quaternionf rotation, Vector3f vector) {
        Vector3f rotated = vector.rotate(new Quaternionf(rotation));
        return new Vec3(rotated.x(), rotated.y(), rotated.z()).normalize();
    }

    private static Vec3 normalizeDown(Vec3 down) {
        if (!isUsable(down)) {
            return GravityState.DEFAULT_DOWN;
        }
        return down.normalize();
    }

    private static Vec3 axisToMinecraft(Vector3d axis) {
        return new Vec3(axis.x, axis.y, axis.z);
    }

    private static boolean isUsable(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z)
                && vector.lengthSqr() >= EPSILON;
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
