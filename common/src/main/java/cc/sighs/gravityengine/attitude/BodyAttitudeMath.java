package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Pure direction/rotation operations shared by the body-attitude layer.
 *
 * <p>This type owns no attitude-policy targets and no integrator. Flight
 * heading intent lives in {@link FlightIntentResolver}, and dynamic
 * {@code q + L_world} integration lives in {@link AngularDynamicsSolver}.</p>
 *
 * <p>In particular there is no gravity-tangent projection helper here: a
 * desired flight direction must not be constrained to a gravity plane, and
 * gravity strength must not scale heading authority.</p>
 */
public final class BodyAttitudeMath {
    private BodyAttitudeMath() {}

    /** Shortest-arc world-space rotation vector for desired * inverse(current). */
    public static Vec3d rotationError(
            Quatd desiredWorldFromBody,
            Quatd currentWorldFromBody,
            double epsilon
    ) {
        Quatd desired = normalized(desiredWorldFromBody, epsilon);
        Quatd current = normalized(currentWorldFromBody, epsilon);
        Quatd error = desired
                .multiply(current.conjugate())
                .normalized();
        if (error.w() < 0.0D) {
            error = new Quatd(
                    -error.x(),
                    -error.y(),
                    -error.z(),
                    -error.w()
            );
        }
        double vectorLength = Math.sqrt(error.x() * error.x()
                + error.y() * error.y() + error.z() * error.z());
        if (vectorLength <= epsilon) {
            return Vec3d.ZERO;
        }
        double angle = 2.0D * Math.atan2(vectorLength, clamp(error.w(), 0.0D, 1.0D));
        return new Vec3d(error.x(), error.y(), error.z()).multiply(angle / vectorLength);
    }

    public static Vec3d bodyUp(Quatd worldFromBody) {
        return AttitudeSpaceTransform.bodyUpWorld(worldFromBody);
    }

    public static Vec3d bodyForward(Quatd worldFromBody) {
        return AttitudeSpaceTransform.bodyForwardWorld(worldFromBody);
    }

    /**
     * Shortest world-space direction rotation, in radians.
     *
     * <p>At exactly pi the caller supplies a retained body-derived
     * perpendicular axis; a deterministic canonical perpendicular is only the
     * final fallback. World up and gravity up must never be used as that
     * fallback.</p>
     */
    static Vec3d directionRotationError(
            Vec3d from,
            Vec3d to,
            Vec3d fallbackAxis,
            double epsilon
    ) {
        Vec3d a = normalizeOrFallback(from, AttitudeSpaceTransform.BODY_UP, epsilon);
        Vec3d b = normalizeOrFallback(to, a, epsilon);
        Vec3d cross = a.cross(b);
        double sine = cross.length();
        double cosine = clamp(a.dot(b), -1, 1);
        double angle = Math.atan2(sine, cosine);
        if (sine > epsilon) return cross.multiply(angle / sine);
        if (cosine >= 0) return Vec3d.ZERO;
        Vec3d axis = projectedUnit(fallbackAxis, a, epsilon);
        if (axis == null) axis = deterministicPerpendicular(a, epsilon);
        return axis.multiply(angle);
    }

    private static Vec3d deterministicPerpendicular(Vec3d normal, double epsilon) {
        Vec3d[] candidates = {
                AttitudeSpaceTransform.BODY_LEFT,
                AttitudeSpaceTransform.BODY_UP,
                AttitudeSpaceTransform.BODY_FORWARD
        };
        Vec3d best = candidates[0];
        double bestDot = Math.abs(normal.dot(best));
        for (int i = 1; i < candidates.length; i++) {
            double dot = Math.abs(normal.dot(candidates[i]));
            if (dot < bestDot) {
                best = candidates[i];
                bestDot = dot;
            }
        }
        Vec3d projected = projectedUnit(best, normal, epsilon);
        if (projected == null) {
            throw new IllegalArgumentException("cannot construct perpendicular axis");
        }
        return projected;
    }

    private static Vec3d projectedUnit(Vec3d vector, Vec3d planeNormal, double epsilon) {
        return AttitudeSpaceTransform.projectedUnit(
                vector, planeNormal, epsilon);
    }

    private static Vec3d normalizeOrFallback(Vec3d value, Vec3d fallback, double epsilon) {
        Vec3d normalized = normalizedOrNull(value, epsilon);
        if (normalized != null) return normalized;
        normalized = normalizedOrNull(fallback, epsilon);
        if (normalized == null) throw new IllegalArgumentException("fallback vector is degenerate");
        return normalized;
    }

    private static Vec3d normalizedOrNull(Vec3d vector, double epsilon) {
        if (vector == null || !vector.isFinite()
                || !Double.isFinite(vector.lengthSquared())) {
            return null;
        }
        double length = Math.hypot(Math.hypot(vector.x(), vector.y()), vector.z());
        if (!Double.isFinite(length) || length <= epsilon) return null;
        return vector.multiply(1.0D / length);
    }

    private static Quatd normalized(Quatd value, double epsilon) {
        Objects.requireNonNull(value, "quaternion");
        double lengthSquared = value.lengthSquared();
        if (!value.isFinite()
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= epsilon * epsilon) {
            throw new IllegalArgumentException("quaternion must be finite and non-degenerate");
        }
        return value.normalized();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
