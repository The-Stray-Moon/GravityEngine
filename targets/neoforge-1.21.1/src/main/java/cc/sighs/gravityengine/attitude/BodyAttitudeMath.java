package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Objects;

/** Pure quaternion/vector operations shared by the body-attitude controller. */
public final class BodyAttitudeMath {
    private BodyAttitudeMath() {}

    /** Shortest-arc world-space rotation vector for desired * inverse(current). */
    public static Vec3 rotationError(
            Quaterniond desiredWorldFromBody,
            Quaterniond currentWorldFromBody,
            double epsilon
    ) {
        Quaterniond desired = normalized(desiredWorldFromBody, epsilon);
        Quaterniond current = normalized(currentWorldFromBody, epsilon);
        Quaterniond error = new Quaterniond(desired)
                .mul(new Quaterniond(current).conjugate())
                .normalize();
        if (error.w < 0.0D) {
            error.set(-error.x, -error.y, -error.z, -error.w);
        }
        double vectorLength = Math.sqrt(error.x * error.x
                + error.y * error.y + error.z * error.z);
        if (vectorLength <= epsilon) {
            return Vec3.ZERO;
        }
        double angle = 2.0D * Math.atan2(vectorLength, clamp(error.w, 0.0D, 1.0D));
        return new Vec3(error.x, error.y, error.z).scale(angle / vectorLength);
    }

    public static Vec3 bodyUp(Quaterniond worldFromBody) {
        return AttitudeSpaceTransform.bodyUpWorld(worldFromBody);
    }

    public static Vec3 bodyForward(Quaterniond worldFromBody) {
        return AttitudeSpaceTransform.bodyForwardWorld(worldFromBody);
    }

    static Vec3 elytraDesiredForward(
            Quaterniond current,
            GravityFrame frame,
            Vec3 lookForwardWorld,
            Vec3 velocityWorld,
            BodyAttitudeConfigSnapshot config
    ) {
        double epsilon = config.vectorEpsilon();

        /*
         * Qbody remains the canonical humanoid/root pose. Elytra dynamics
         * consumes the derived flight longitudinal axis, not humanoid +Z.
         */
        Vec3 bodyForward =
                AttitudeSpaceTransform.elytraForwardWorld(current);

        Vec3 look = normalizedOrNull(lookForwardWorld, epsilon);
        if (look == null) {
            look = bodyForward;
        }

        double speed = magnitude(velocityWorld);
        double velocityWeight =
                config.elytraVelocityAlignment()
                        * smoothstep(
                        config.elytraVelocityAlignStartSpeed(),
                        config.elytraVelocityAlignFullSpeed(),
                        speed
                );

        Vec3 velocityDirection =
                normalizedOrNull(velocityWorld, epsilon);

        Vec3 blended =
                velocityDirection == null
                        ? look
                        : look.scale(1.0D - velocityWeight)
                        .add(velocityDirection.scale(velocityWeight));

        Vec3 desiredForward =
                normalizedOrNull(blended, epsilon);

        if (desiredForward == null) {
            desiredForward = normalizedOrNull(look, epsilon);
        }

        if (desiredForward == null) {
            desiredForward = normalizedOrNull(bodyForward, epsilon);
        }

        if (desiredForward == null) {
            desiredForward = normalizeOrFallback(
                    frame.forward(),
                    AttitudeSpaceTransform.BODY_FORWARD,
                    epsilon
            );
        }

        Vec3 down = frame.down();

        Vec3 tangent =
                projectedUnit(desiredForward, down, epsilon);

        if (tangent == null) {
            tangent = projectedUnit(bodyForward, down, epsilon);
        }

        if (tangent == null) {
            tangent = deterministicPerpendicular(down, epsilon);
        }

        /*
         * A retained GravityFrame direction at zero gravity must not invent a
         * physical flight plane. Tangent authority grows smoothly and reaches
         * full strength at Vanilla gravity.
         */
        double gravityAuthority =
                smoothstep(
                        0.0D,
                        cc.sighs.gravityengine.gravity.GravityState.VANILLA_STRENGTH,
                        Math.max(0.0D, frame.strength())
                );

        return normalizeOrFallback(
                desiredForward.scale(1.0D - gravityAuthority)
                        .add(tangent.scale(gravityAuthority)),
                desiredForward,
                epsilon
        );
    }

    /** Shortest world-space direction rotation, in radians. At pi choose a stable
     * body-derived perpendicular axis, then a deterministic canonical fallback. */
    static Vec3 directionRotationError(Vec3 from, Vec3 to, Vec3 fallbackAxis, double epsilon) {
        Vec3 a = normalizeOrFallback(from, AttitudeSpaceTransform.BODY_UP, epsilon);
        Vec3 b = normalizeOrFallback(to, a, epsilon);
        Vec3 cross = a.cross(b);
        double sine = cross.length();
        double cosine = clamp(a.dot(b), -1, 1);
        double angle = Math.atan2(sine, cosine);
        if (sine > epsilon) return cross.scale(angle / sine);
        if (cosine >= 0) return Vec3.ZERO;
        Vec3 axis = projectedUnit(fallbackAxis, a, epsilon);
        if (axis == null) axis = deterministicPerpendicular(a, epsilon);
        return axis.scale(angle);
    }

    static Quaterniond integrateWorldAngularVelocity(
            Quaterniond current,
            Vec3 angularVelocityWorld,
            double dtSeconds,
            double epsilon
    ) {
        double speed = angularVelocityWorld.length();
        if (speed <= epsilon) {
            return new Quaterniond(current).normalize();
        }
        double angle = speed * dtSeconds;
        Vec3 axis = angularVelocityWorld.scale(1.0D / speed);
        double halfAngle = angle * 0.5D;
        double sine = Math.sin(halfAngle);
        Quaterniond delta = new Quaterniond(
                axis.x * sine, axis.y * sine, axis.z * sine, Math.cos(halfAngle)
        );
        // Angular velocity is world-space, so the increment pre-multiplies q.
        return delta.mul(new Quaterniond(current)).normalize();
    }

    static Vec3 clampMagnitude(Vec3 value, double maximum) {
        double length = magnitude(value);
        if (length <= maximum) return value;
        return value.scale(maximum / length);
    }

    private static Vec3 deterministicPerpendicular(Vec3 normal, double epsilon) {
        Vec3[] candidates = {AttitudeSpaceTransform.BODY_LEFT, AttitudeSpaceTransform.BODY_UP, AttitudeSpaceTransform.BODY_FORWARD};
        Vec3 best = candidates[0];
        double bestDot = Math.abs(normal.dot(best));
        for (int i = 1; i < candidates.length; i++) {
            double dot = Math.abs(normal.dot(candidates[i]));
            if (dot < bestDot) {
                best = candidates[i];
                bestDot = dot;
            }
        }
        Vec3 projected = projectedUnit(best, normal, epsilon);
        if (projected == null) {
            throw new IllegalArgumentException("cannot construct perpendicular axis");
        }
        return projected;
    }

    private static Vec3 projectedUnit(Vec3 vector, Vec3 planeNormal, double epsilon) {
        return AttitudeSpaceTransform.projectedUnit(
                vector, planeNormal, epsilon);
    }

    private static Vec3 normalizedOrNull(Vec3 vector, double epsilon) {
        if (vector == null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) return null;
        double length = magnitude(vector);
        if (!Double.isFinite(length) || length <= epsilon) return null;
        return vector.scale(1.0D / length);
    }

    private static boolean isUsable(Vec3 vector, double epsilon) {
        return normalizedOrNull(vector, epsilon) != null;
    }

    private static Vec3 normalizeOrFallback(Vec3 value, Vec3 fallback, double epsilon) {
        Vec3 normalized = normalizedOrNull(value, epsilon);
        if (normalized != null) return normalized;
        normalized = normalizedOrNull(fallback, epsilon);
        if (normalized == null) throw new IllegalArgumentException("fallback vector is degenerate");
        return normalized;
    }

    private static Quaterniond normalized(Quaterniond value, double epsilon) {
        Objects.requireNonNull(value, "quaternion");
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y)
                || !Double.isFinite(value.z) || !Double.isFinite(value.w)
                || !Double.isFinite(lengthSquared) || lengthSquared <= epsilon * epsilon) {
            throw new IllegalArgumentException("quaternion must be finite and non-degenerate");
        }
        return new Quaterniond(value).normalize();
    }

    private static Vec3 rotate(Quaterniond quaternion, Vec3 vector) {
        Quaterniond q = normalized(quaternion, 1.0E-12D);
        Vector3d rotated = new Vector3d(vector.x, vector.y, vector.z).rotate(q);
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    private static double smoothstep(double start, double end, double value) {
        double t = clamp((value - start) / (end - start), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double magnitude(Vec3 vector) {
        return Math.hypot(Math.hypot(vector.x, vector.y), vector.z);
    }
}
