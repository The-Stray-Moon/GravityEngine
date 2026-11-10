package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Pure reference-space translation between gravity-local axes, the absolute
 * body quaternion and model-local look joints.
 *
 * <p>This layer is deliberately entity-free: it never reads a
 * {@code Player}, {@code LivingEntity}, renderer, key binding, network payload
 * or BodyAttitude lifecycle.  All coordinate conventions live here exactly
 * once so renderers, controllers and mixins never re-derive inverse-quaternion
 * or tangent-projection math with their own sign conventions.</p>
 *
 * <p>Conventions (identical to {@code GravityFrame}): canonical body local
 * +X is left, +Y is up, +Z is forward.  Gravity-local +X is
 * {@code frame.left}, +Y is {@code frame.up} and +Z is {@code frame.forward}.
 * Minecraft model yaw is the signed angle around body up; positive pitch looks
 * down, matching Minecraft's negative-look-up convention.</p>
 */
public final class AttitudeSpaceTransform {
    public static final Vec3d BODY_LEFT =
            new Vec3d(1.0D, 0.0D, 0.0D);
    public static final Vec3d BODY_UP =
            new Vec3d(0.0D, 1.0D, 0.0D);
    public static final Vec3d BODY_FORWARD =
            new Vec3d(0.0D, 0.0D, 1.0D);

    private static final double EPSILON_SQR = 1.0E-20D;
    private static final double LOOK_POLE_HORIZONTAL_SQR = 1.0E-12D;

    private AttitudeSpaceTransform() {}

    /** Yaw/pitch pairs are Minecraft model-local joints, not euler body state. */
    public record LocalLookAngles(
            float yawDegrees,
            float pitchDegrees
    ) {}

    /**
     * One-time projection into the receiving gravity-local Vanilla scalars.
     * No body-local copy is retained after the actor-view ownership release.
     */
    public record ReleaseLookRebase(
            float sourceYaw,
            float sourcePitch
    ) {
        public ReleaseLookRebase {
            requireFinite(sourceYaw, "sourceYaw");
            requireFinite(sourcePitch, "sourcePitch");
        }
    }

    /** Gravity-tangent displacement heading and its squared tangent length. */
    public record TangentHeading(
            float yawDegrees,
            double tangentLengthSquared
    ) {}

    /**
     * Maps a gravity-local vector into world space through the canonical
     * frame basis (left/up/forward).  The generic world/local algebra is
     * delegated to the single {@code OrthonormalFrame3d} math authority.
     */
    public static Vec3d gravityLocalToWorld(
            GravityFrame frame,
            Vec3d local
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(local, "local");
        return frame.localToWorld(local);
    }

    /**
     * Projects a world vector onto the canonical gravity-local frame basis
     * (left/up/forward).  The generic world/local algebra is delegated to the
     * single {@code OrthonormalFrame3d} math authority.
     */
    public static Vec3d worldToGravityLocal(
            GravityFrame frame,
            Vec3d world
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(world, "world");
        return frame.worldToLocal(world);
    }

    /** Rotates a canonical body-local vector into world space. */
    public static Vec3d bodyLocalToWorld(
            Quatd worldFromBody,
            Vec3d bodyLocal
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(bodyLocal, "bodyLocal");
        return rotate(normalized(worldFromBody), bodyLocal);
    }

    /** Rotates a world vector into canonical body-local coordinates. */
    public static Vec3d worldToBodyLocal(
            Quatd worldFromBody,
            Vec3d world
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(world, "world");
        Quatd bodyFromWorld =
                normalized(worldFromBody).conjugate();
        return rotate(bodyFromWorld, world);
    }

    public static Vec3d bodyForwardWorld(
            Quatd worldFromBody
    ) {
        return bodyLocalToWorld(worldFromBody, BODY_FORWARD);
    }

    public static Vec3d bodyUpWorld(
            Quatd worldFromBody
    ) {
        return bodyLocalToWorld(worldFromBody, BODY_UP);
    }

    public static Vec3d bodyLeftWorld(
            Quatd worldFromBody
    ) {
        return bodyLocalToWorld(worldFromBody, BODY_LEFT);
    }

    /**
     * Elytra dynamics uses a flight/body frame embedded in the canonical
     * humanoid/root attitude.
     *
     * <p>{@code worldFromBody} deliberately keeps one meaning across
     * FREE_ATTITUDE and ELYTRA_ALIGNED: it is the canonical humanoid/root
     * actor pose consumed by presentation, replication and persistence.
     *
     * <p>The aerodynamic Elytra frame is a fixed local remapping:
     *
     * <pre>
     * flight +X left     = humanoid +X
     * flight +Y dorsal   = humanoid -Z
     * flight +Z forward  = humanoid +Y
     * flight -Y belly    = humanoid +Z
     * </pre>
     *
     * This is equivalent to deriving a physical flight quaternion as
     * {@code worldFromFlight = worldFromBody * rotationX(-90deg)}, without
     * changing the stored Qbody representation.
     */
    public static Vec3d elytraLeftWorld(
            Quatd worldFromBody
    ) {
        return bodyLeftWorld(worldFromBody);
    }

    public static Vec3d elytraDorsalWorld(
            Quatd worldFromBody
    ) {
        return bodyForwardWorld(worldFromBody).negate();
    }

    public static Vec3d elytraForwardWorld(
            Quatd worldFromBody
    ) {
        return bodyUpWorld(worldFromBody);
    }

    public static Vec3d elytraBellyWorld(
            Quatd worldFromBody
    ) {
        return bodyForwardWorld(worldFromBody);
    }

    /**
     * Converts a world look direction into the model-local yaw/pitch joints a
     * canonical Minecraft body consumes.  Yaw is
     * {@code atan2(-left, forward)} around body up; pitch is negative when
     * looking upward.  A degenerate direction returns the caller's fallback.
     */
    public static LocalLookAngles worldLookToBodyAngles(
            Quatd worldFromBody,
            Vec3d worldDirection,
            LocalLookAngles fallback
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(worldDirection, "worldDirection");
        Objects.requireNonNull(fallback, "fallback");

        Vec3d local = worldToBodyLocal(worldFromBody, worldDirection);
        return localLookAngles(local, fallback);
    }

    /**
     * Converts a world look direction into gravity-local yaw/pitch joints for
     * the supplied frame.  This is the inverse of
     * {@code GravityLocalLook.toWorld(frame, yaw, pitch)} and is used when a
     * handoff returns reference-space look ownership to vanilla/gravity-local
     * scalars.
     */
    public static LocalLookAngles worldLookToGravityAngles(
            GravityFrame frame,
            Vec3d worldDirection,
            LocalLookAngles fallback
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(worldDirection, "worldDirection");
        Objects.requireNonNull(fallback, "fallback");
        Vec3d local = worldToGravityLocal(frame, worldDirection);
        return localLookAngles(local, fallback);
    }

    /** Inverse of {@link #worldLookToBodyAngles}: model joints to a world look vector. */
    public static Vec3d worldLookFromBodyAngles(
            Quatd worldFromBody,
            LocalLookAngles localLook
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(localLook, "localLook");
        return bodyLocalToWorld(worldFromBody, bodyLocalLookForward(localLook));
    }

    /**
     * Projects the accepted semantic view into gravity-local Vanilla scalars at
     * an ownership handoff. Explicit angles are inverse-transform fallbacks,
     * never another input stream; pending input belongs to the control boundary.
     */
    public static ReleaseLookRebase releaseLookRebase(
            GravityFrame frame,
            Quatd worldFromBody,
            BodyRelativeViewState view,
            float explicitYaw,
            float explicitPitch
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(view, "view");
        LocalLookAngles local = view.explicitLocalLook(
                explicitYaw, explicitPitch);
        Vec3d worldLook = view.initialized() ? view.semantic(worldFromBody).forward()
                : worldLookFromBodyAngles(worldFromBody, local);
        LocalLookAngles fallback = new LocalLookAngles(explicitYaw, explicitPitch);
        if (view.initialized()) {
            // At a look pole the forward vector cannot determine yaw. The
            // retiring controller still has a tangent orientation; stale scalar
            // carriers must not become authority for that remaining degree of freedom.
            Vec3d left = frame.worldToLocal(view.semantic(worldFromBody).left());
            fallback = new LocalLookAngles((float) Math.toDegrees(Math.atan2(left.z(), left.x())), local.pitchDegrees());
        }
        LocalLookAngles gravityAngles = worldLookToGravityAngles(
                frame,
                worldLook,
                fallback);
        return new ReleaseLookRebase(gravityAngles.yawDegrees(), gravityAngles.pitchDegrees());
    }

    /**
     * Canonical body-local forward for Minecraft model yaw/pitch.  With yaw=0,
     * pitch=0 the result is canonical +Z (body forward).  Positive yaw turns
     * the view toward body-local -X (left); positive pitch looks down.
     */
    public static Vec3d bodyLocalLookForward(LocalLookAngles localLook) {
        Objects.requireNonNull(localLook, "localLook");
        double yaw = Math.toRadians(localLook.yawDegrees());
        double pitch = Math.toRadians(localLook.pitchDegrees());
        double cosPitch = Math.cos(pitch);
        return new Vec3d(
                -Math.sin(yaw) * cosPitch,
                -Math.sin(pitch),
                Math.cos(yaw) * cosPitch
        );
    }

    /** Rlocal = Ry(-yaw) Rx(pitch), acting on canonical +Z forward/+Y up. */
    public static Quatd localViewRotation(LocalLookAngles look) {
        return Quatd
                .rotationY(-Math.toRadians(look.yawDegrees()))
                .rotateX(Math.toRadians(look.pitchDegrees()));
    }

    private static LocalLookAngles localLookAngles(
            Vec3d local,
            LocalLookAngles fallback
    ) {
        if (local.lengthSquared() <= EPSILON_SQR) {
            return fallback;
        }
        double horizontal = Math.hypot(local.x(), local.z());
        float yaw = horizontal * horizontal <= LOOK_POLE_HORIZONTAL_SQR
                ? fallback.yawDegrees()
                : (float) Math.toDegrees(Math.atan2(-local.x(), local.z()));
        float pitch = (float) Math.toDegrees(
                Math.atan2(-local.y(), horizontal)
        );
        return new LocalLookAngles(yaw, pitch);
    }

    /**
     * Gravity-local heading of a world displacement projected onto the frame
     * tangent plane, used for {@code yBodyRot} translation.
     */
    public static TangentHeading gravityTangentHeading(
            GravityFrame frame,
            Vec3d worldDisplacement
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(worldDisplacement, "worldDisplacement");
        Vec3d local = worldToGravityLocal(frame, worldDisplacement);
        double tangentSqr = local.x() * local.x() + local.z() * local.z();
        float yaw = tangentSqr <= EPSILON_SQR
                ? 0.0F
                : (float) Math.toDegrees(Math.atan2(-local.x(), local.z()));
        return new TangentHeading(yaw, tangentSqr);
    }

    /** Removes the component parallel to a normalized axis. */
    public static Vec3d rejectFromAxis(
            Vec3d value,
            Vec3d axis
    ) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(axis, "axis");
        double lengthSqr = axis.lengthSquared();
        if (!Double.isFinite(lengthSqr) || lengthSqr <= EPSILON_SQR) {
            return value;
        }
        Vec3d unit = axis.multiply(1.0D / Math.sqrt(lengthSqr));
        return value.subtract(unit.multiply(value.dot(unit)));
    }

    /**
     * Unit tangent projection of {@code value} onto the plane perpendicular to
     * {@code planeNormal}.
     *
     * <p>This is the single shared projection used by movement,
     * {@code BodyAttitudeMath} and gravity body-turn heading consumers so a
     * near-pole degenerate projection is handled with one epsilon policy per
     * caller.  Returns {@code null} when either input is unusable or the
     * projection is shorter than {@code epsilon} (length threshold).</p>
     */
    public static Vec3d projectedUnit(
            Vec3d value,
            Vec3d planeNormal,
            double epsilon
    ) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(planeNormal, "planeNormal");
        if (!Double.isFinite(epsilon) || epsilon <= 0.0D) {
            throw new IllegalArgumentException(
                    "epsilon must be finite and positive");
        }
        Vec3d unitNormal = normalizedOrNull(planeNormal, epsilon);
        if (unitNormal == null) return null;
        Vec3d projected = value.subtract(
                unitNormal.multiply(value.dot(unitNormal)));
        return normalizedOrNull(projected, epsilon);
    }

    /**
     * Signed angle from {@code from} to {@code to} around {@code axis}.
     *
     * <p>The cross-product sine is {@code axis dot (from x to)}.  For the
     * default frame with a world-XZ pair that is
     * {@code from.z() * to.x() - from.x() * to.z()}.  In particular the Elytra call
     * {@code signedAngleAround(up, lookTangent, velocityTangent)} yields
     * {@code atan2(velocity.x() * look.z() - velocity.z() * look.x(), look dot
     * velocity)}, matching the Vanilla 21.1.249
     * {@code signum(cross) * acos(cosine)} value on non-degenerate pairs.
     * Implementation is fixed and tested; the comment documents that sign.</p>
     */
    public static double signedAngleAround(
            Vec3d axis,
            Vec3d from,
            Vec3d to
    ) {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Vec3d unitAxis = normalize(axis);
        Vec3d a = rejectFromAxis(from, unitAxis);
        Vec3d b = rejectFromAxis(to, unitAxis);
        if (a.lengthSquared() <= EPSILON_SQR
                || b.lengthSquared() <= EPSILON_SQR) {
            return 0.0D;
        }
        a = normalize(a);
        b = normalize(b);
        double sin = unitAxis.dot(a.cross(b));
        double cos = Math.max(-1.0D, Math.min(1.0D, a.dot(b)));
        return Math.atan2(sin, cos);
    }

    private static Quatd normalized(Quatd q) {
        double lengthSqr = q.lengthSquared();
        if (!Double.isFinite(q.x()) || !Double.isFinite(q.y())
                || !Double.isFinite(q.z()) || !Double.isFinite(q.w())
                || !Double.isFinite(lengthSqr)
                || lengthSqr <= EPSILON_SQR) {
            throw new IllegalArgumentException(
                    "quaternion must be finite and non-degenerate"
            );
        }
        return q.normalized();
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static Vec3d normalize(Vec3d value) {
        double lengthSqr = value.lengthSquared();
        if (!Double.isFinite(lengthSqr)
                || lengthSqr <= EPSILON_SQR) {
            throw new IllegalArgumentException(
                    "vector must be finite and non-degenerate"
            );
        }
        return value.multiply(1.0D / Math.sqrt(lengthSqr));
    }

    private static Vec3d normalizedOrNull(Vec3d value, double epsilon) {
        if (value == null || !Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())) {
            return null;
        }
        double lengthSqr = value.lengthSquared();
        if (!Double.isFinite(lengthSqr) || lengthSqr <= epsilon * epsilon) {
            return null;
        }
        return value.multiply(1.0D / Math.sqrt(lengthSqr));
    }

    private static Vec3d rotate(
            Quatd rotation,
            Vec3d vector
    ) {
        Vec3d out = rotation.transform(
                new Vec3d(vector.x(), vector.y(), vector.z())
        );
        return new Vec3d(out.x(), out.y(), out.z());
    }
}
