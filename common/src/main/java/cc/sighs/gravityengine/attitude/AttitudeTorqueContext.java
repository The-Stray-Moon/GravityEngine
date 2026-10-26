package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Immutable evidence handed to one attitude torque policy for one solver
 * substep.
 *
 * <p>Every operand carries its space, unit and sample time: {@code
 * worldFromBody} is body-local to world at the substep start,
 * {@code angularVelocityWorld} is derived {@code omega_world} in rad/s,
 * {@code angularMomentumWorld} is {@code L_world} in
 * {@code inertia-unit * rad / s}, {@code inertia} is the body-space effective
 * inertia and {@code dtSeconds} is the substep duration in seconds. Policies
 * read this context and contribute torque; they never write physical
 * state.</p>
 */
public record AttitudeTorqueContext(
        Quatd worldFromBody,
        Vec3d angularVelocityWorld,
        Vec3d angularMomentumWorld,
        EffectiveAngularInertia inertia,
        FlightIntent flightIntent,
        BodyAttitudeInput input,
        BodyAttitudeControlProfile controlProfile,
        BodyAttitudeConfigSnapshot config,
        double dtSeconds
) {
    public AttitudeTorqueContext {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularVelocityWorld, "angularVelocityWorld");
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
        Objects.requireNonNull(inertia, "inertia");
        Objects.requireNonNull(flightIntent, "flightIntent");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(controlProfile, "controlProfile");
        Objects.requireNonNull(config, "config");
        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0D) {
            throw new IllegalArgumentException(
                    "dtSeconds must be finite and positive");
        }
    }
}
