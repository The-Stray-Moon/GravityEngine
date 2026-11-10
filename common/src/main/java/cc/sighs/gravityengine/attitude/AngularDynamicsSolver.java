package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * The single integrator owner for every GE-owned dynamic attitude actor.
 *
 * <p>Canonical quantities (see {@code AGENTS.md}): {@code q = worldFromBody},
 * {@code L_world} in {@code inertia-unit * rad / s}, {@code I_body} isotropic
 * effective inertia and the ephemeral {@code tau_world} accumulated for one
 * solver step. Angles are radians and integration time is seconds.</p>
 *
 * <p>For a substep whose torque is treated as constant this uses the midpoint
 * (semi-implicit) form:</p>
 *
 * <pre>
 * L_half  = L0 + 0.5 * tau * dt
 * omega_mid = L_half / I
 * q1      = exp_world(omega_mid * dt) * q0
 * L1      = L0 + tau * dt
 * </pre>
 *
 * <p>The world-space angular increment pre-multiplies the current quaternion,
 * matching the engine's {@code worldFromBody} convention. A torque-free
 * substep preserves {@code L_world} exactly; under isotropic inertia that also
 * preserves the derived {@code omega_world}, but a general rigid body only
 * guarantees world-space {@code L_world} conservation, so callers and
 * documentation must not state the stronger isotropic claim as a general
 * contract.</p>
 *
 * <p>Non-finite torques, invalid time steps and degenerate quaternions are
 * rejected at this boundary instead of being silently clamped.</p>
 */
public final class AngularDynamicsSolver {
    private AngularDynamicsSolver() {}

    public static AngularDynamicsStep integrate(
            Quatd worldFromBody,
            AngularMomentumState dynamics,
            Vec3d torqueWorld,
            double dtSeconds,
            double quaternionEpsilon
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(dynamics, "dynamics");
        Objects.requireNonNull(torqueWorld, "torqueWorld");

        requireFinite(torqueWorld, "torqueWorld");
        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0D) {
            throw new IllegalArgumentException(
                    "dtSeconds must be finite and positive: " + dtSeconds);
        }
        if (!Double.isFinite(quaternionEpsilon)
                || quaternionEpsilon <= 0.0D) {
            throw new IllegalArgumentException(
                    "quaternionEpsilon must be finite and positive");
        }

        Quatd start = requireUnitQuaternion(worldFromBody, quaternionEpsilon);
        EffectiveAngularInertia inertia = dynamics.inertia();
        Vec3d initialMomentum = dynamics.angularMomentumWorld();

        Vec3d halfMomentum =
                initialMomentum.fma(0.5D * dtSeconds, torqueWorld);
        Vec3d midpointAngularVelocity =
                inertia.angularVelocityWorld(start, halfMomentum);

        Quatd nextOrientation =
                preMultiplyWorldRotation(
                        start,
                        midpointAngularVelocity,
                        dtSeconds
                );

        Vec3d nextMomentum =
                initialMomentum.fma(dtSeconds, torqueWorld);

        requireFinite(nextMomentum, "next angular momentum");

        return new AngularDynamicsStep(
                nextOrientation,
                nextMomentum,
                midpointAngularVelocity
        );
    }

    /**
     * World-space exponential increment pre-multiplied onto the current
     * attitude: {@code exp(theta_world) * q0}.
     */
    static Quatd preMultiplyWorldRotation(
            Quatd worldFromBody,
            Vec3d angularVelocityWorld,
            double dtSeconds
    ) {
        double speed = angularVelocityWorld.length();
        if (!Double.isFinite(speed) || speed <= 1.0E-15D) {
            return worldFromBody;
        }
        double angle = speed * dtSeconds;
        Vec3d axis = angularVelocityWorld.multiply(1.0D / speed);
        double halfAngle = angle * 0.5D;
        double sine = Math.sin(halfAngle);
        Quatd delta = new Quatd(
                axis.x() * sine,
                axis.y() * sine,
                axis.z() * sine,
                Math.cos(halfAngle)
        );
        return delta.multiply(worldFromBody).normalized();
    }

    private static Quatd requireUnitQuaternion(
            Quatd value,
            double epsilon
    ) {
        if (!value.isFinite()
                || !Double.isFinite(value.lengthSquared())
                || value.lengthSquared() <= epsilon * epsilon) {
            throw new IllegalArgumentException(
                    "worldFromBody must be finite and non-degenerate");
        }
        return value.normalized();
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite() || !Double.isFinite(value.lengthSquared())) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
