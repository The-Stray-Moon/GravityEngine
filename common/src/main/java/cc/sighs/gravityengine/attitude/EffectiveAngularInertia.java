package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Isotropic effective angular inertia of one GravityEngine-owned dynamic
 * attitude actor.
 *
 * <p><b>Units.</b> The value is expressed in rotational-inertia game units
 * ({@code inertia-unit}). One {@code inertia-unit} is the inertia that turns a
 * torque of one {@code torque-unit} into one radian per second squared. It is
 * deliberately <em>not</em> kilograms, not a Minecraft mass surrogate, not a
 * collider volume and not derived from entity mass.</p>
 *
 * <p>The first implementation stage deliberately restricts the model to a
 * strictly positive finite isotropic scalar. That makes the world inertia
 * independent of attitude and keeps the derivation
 * {@code omega_world = L_world / I}. A general anisotropic tensor would have to
 * satisfy {@code omega_world = inverse(R I_body R^T) L_world} and must not be
 * approximated by this value type.</p>
 */
public record EffectiveAngularInertia(double isotropic) {
    /** One rotational-inertia game unit. */
    public static final EffectiveAngularInertia UNIT =
            new EffectiveAngularInertia(1.0D);

    /** Default GE dynamics inertia until a profile justifies a different value. */
    public static final EffectiveAngularInertia DEFAULT = UNIT;

    public EffectiveAngularInertia {
        if (!Double.isFinite(isotropic) || isotropic <= 0.0D) {
            throw new IllegalArgumentException(
                    "effective angular inertia must be finite and strictly "
                            + "positive: " + isotropic);
        }
    }

    public static EffectiveAngularInertia of(double isotropic) {
        return isotropic == UNIT.isotropic
                ? UNIT
                : new EffectiveAngularInertia(isotropic);
    }

    /**
     * Derived world angular velocity for the isotropic model.
     *
     * <p>The {@code worldFromBody} operand is accepted so the ownership and
     * space contract of a future anisotropic model stays explicit; the
     * isotropic stage must not silently drop the attitude dependency from the
     * interface.</p>
     */
    public Vec3d angularVelocityWorld(
            cc.sighs.gravityengine.math.Quatd worldFromBody,
            Vec3d angularMomentumWorld
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
        return angularMomentumWorld.multiply(1.0D / isotropic);
    }
}
