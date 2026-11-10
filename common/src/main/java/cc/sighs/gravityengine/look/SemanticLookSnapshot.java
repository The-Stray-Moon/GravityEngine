package cc.sighs.gravityengine.look;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import java.util.Objects;

/** One immutable, entity-free gameplay view snapshot.
 * BODY_ATTITUDE uses Qcontroller's complete local frame, with zero local
 * angles in that frame. Forward/up/left retain controller pitch and roll.
 * GRAVITY_LOCAL and VANILLA preserve their existing two-angle input contracts.
 * zeroPitchForward is a derived reference-tangent heading/pole fallback; it is
 * never the controller with an absolute Euler pitch set to zero. */
public record SemanticLookSnapshot(
        Source source,
        OrthonormalFrame3d worldFromLocal,
        AttitudeSpaceTransform.LocalLookAngles requestedLocalLook,
        Vec3d forward,
        Vec3d up,
        Vec3d zeroPitchForward
) {
    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0E-20D;

    public enum Source {
        VANILLA,
        GRAVITY_LOCAL,
        BODY_ATTITUDE
    }

    public SemanticLookSnapshot {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(worldFromLocal, "worldFromLocal");
        Objects.requireNonNull(requestedLocalLook, "requestedLocalLook");
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(zeroPitchForward, "zeroPitchForward");
        requireFinite(requestedLocalLook.yawDegrees(), "requested yaw");
        requireFinite(requestedLocalLook.pitchDegrees(), "requested pitch");
        requireUsableDirection(forward, "forward");
        requireUsableDirection(up, "up");
        requireUsableDirection(zeroPitchForward, "zeroPitchForward");
    }

    /**
     * Active controller offsets compose locally against forward/up. Other paths preserve
     * Vanilla projectile launch direction with the separate pitch-offset
     * convention: horizontal magnitude uses {@code cos(pitch)} while the
     * vertical component uses {@code -sin(pitch + offset)}.  The result is
     * resolved in the same local frame as {@link #requestedLocalLook()} and
     * mapped through {@link #worldFromLocal()}.
     */
    public Vec3d launchDirection(float pitchOffsetDegrees) {
        requireFinite(pitchOffsetDegrees, "pitch offset");
        if (source == Source.BODY_ATTITUDE) {
            double offset = Math.toRadians(pitchOffsetDegrees);
            return forward.multiply(Math.cos(offset))
                    .subtract(up.multiply(Math.sin(offset)));
        }
        double yaw = Math.toRadians(requestedLocalLook.yawDegrees());
        double pitch = Math.toRadians(requestedLocalLook.pitchDegrees());
        double offsetPitch = Math.toRadians(
                requestedLocalLook.pitchDegrees() + pitchOffsetDegrees);
        return localDirectionToWorld(new Vec3d(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(offsetPitch),
                Math.cos(yaw) * Math.cos(pitch)
        ));
    }

    /**
     * Maps one canonical local direction (body-local or reference-local
     * left/up/forward coordinates) through this snapshot's local frame.
     * Under VANILLA source the local frame is the world-axis identity, so
     * the input must already be expressed in world axes.
     */
    public Vec3d localDirectionToWorld(Vec3d local) {
        Objects.requireNonNull(local, "local");
        requireUsableDirection(local, "local direction");
        return worldFromLocal.localToWorld(local);
    }

    public Vec3d left() {
        return up.cross(forward).normalized();
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireUsableDirection(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = vector.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_DIRECTION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    name + " must be a non-degenerate direction");
        }
    }
}
