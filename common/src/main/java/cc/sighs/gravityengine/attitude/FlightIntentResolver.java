package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Resolves one solver sample's {@link FlightIntent} from semantic look and
 * optional velocity alignment.
 *
 * <p>Deliberately independent of gravity: the desired flight direction is not
 * projected onto a gravity tangent plane, and gravity strength does not scale
 * its authority. Low speed cannot turn a noisy velocity direction into player
 * intent because alignment fades in with speed and the semantic look remains
 * authoritative below the configured start speed. Sideslip and reverse flight
 * are valid outcomes of the resulting torque policy.</p>
 */
public final class FlightIntentResolver {
    private FlightIntentResolver() {}

    public static FlightIntent resolve(
            Quatd worldFromBody,
            Vec3d lookForwardWorld,
            Vec3d velocityWorld,
            BodyAttitudeConfigSnapshot config
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(lookForwardWorld, "lookForwardWorld");
        Objects.requireNonNull(velocityWorld, "velocityWorld");
        Objects.requireNonNull(config, "config");

        double epsilon = config.vectorEpsilon();

        /*
         * Elytra consumes the derived flight longitudinal axis rather than
         * humanoid +Z; Qbody stays the canonical root pose.
         */
        Vec3d bodyForward =
                AttitudeSpaceTransform.elytraForwardWorld(worldFromBody);

        Vec3d look = normalizedOrNull(lookForwardWorld, epsilon);
        if (look == null) {
            look = normalizedOrNull(bodyForward, epsilon);
        }

        double speed = magnitude(velocityWorld);
        double velocityWeight =
                config.elytraVelocityAlignment()
                        * smoothstep(
                        config.elytraVelocityAlignStartSpeed(),
                        config.elytraVelocityAlignFullSpeed(),
                        speed
                );

        Vec3d velocityDirection = normalizedOrNull(velocityWorld, epsilon);

        Vec3d blended = velocityDirection == null
                ? look
                : look.multiply(1.0D - velocityWeight)
                .add(velocityDirection.multiply(velocityWeight));

        Vec3d desiredForward = normalizedOrNull(blended, epsilon);
        if (desiredForward == null) {
            desiredForward = look;
        }
        if (desiredForward == null) {
            desiredForward = normalizedOrNull(bodyForward, epsilon);
        }
        if (desiredForward == null) {
            desiredForward = AttitudeSpaceTransform.BODY_FORWARD;
        }

        return new FlightIntent(
                desiredForward,
                look == null ? desiredForward : look,
                velocityWorld,
                velocityWeight
        );
    }

    private static Vec3d normalizedOrNull(Vec3d vector, double epsilon) {
        if (vector == null
                || !vector.isFinite()
                || !Double.isFinite(vector.lengthSquared())) {
            return null;
        }
        double length = magnitude(vector);
        if (!Double.isFinite(length) || length <= epsilon) {
            return null;
        }
        return vector.multiply(1.0D / length);
    }

    private static double smoothstep(double start, double end, double value) {
        double t = (value - start) / (end - start);
        t = Math.max(0.0D, Math.min(1.0D, t));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double magnitude(Vec3d vector) {
        return Math.hypot(Math.hypot(vector.x(), vector.y()), vector.z());
    }
}
