package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Canonical persistent angular dynamic state of one GE-owned dynamic attitude
 * actor.
 *
 * <p>{@code angularMomentumWorld} is the world-space angular momentum
 * {@code L_world} in {@code inertia-unit * rad / s}. It is the only durable
 * dynamic rotational state; {@code omega_world} is always derived from
 * {@code q + L + I} and never stored beside it.</p>
 *
 * <p>{@code tau_world} is integrated into {@code L_world} by
 * {@link AngularDynamicsSolver} for exactly one substep and never appears in
 * this value.</p>
 */
public record AngularMomentumState(
        Vec3d angularMomentumWorld,
        EffectiveAngularInertia inertia
) {
    public AngularMomentumState {
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
        Objects.requireNonNull(inertia, "inertia");
        if (!angularMomentumWorld.isFinite()
                || !Double.isFinite(angularMomentumWorld.lengthSquared())) {
            throw new IllegalArgumentException(
                    "angularMomentumWorld must be finite: "
                            + angularMomentumWorld);
        }
    }

    /** Explicit zero-momentum initialization for a dynamic ownership handoff. */
    public static AngularMomentumState rest(EffectiveAngularInertia inertia) {
        return new AngularMomentumState(Vec3d.ZERO, inertia);
    }

    public static AngularMomentumState of(
            Vec3d angularMomentumWorld,
            EffectiveAngularInertia inertia
    ) {
        return new AngularMomentumState(angularMomentumWorld, inertia);
    }

    /** Adopts the current profile inertia while preserving the durable momentum. */
    public AngularMomentumState withInertia(EffectiveAngularInertia next) {
        Objects.requireNonNull(next, "inertia");
        return next.equals(inertia)
                ? this
                : new AngularMomentumState(angularMomentumWorld, next);
    }

    /** Derived {@code omega_world}; never an independent authority. */
    public Vec3d angularVelocityWorld(Quatd worldFromBody) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        return inertia.angularVelocityWorld(worldFromBody, angularMomentumWorld);
    }
}
