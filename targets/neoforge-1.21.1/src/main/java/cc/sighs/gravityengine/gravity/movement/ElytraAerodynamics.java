package cc.sighs.gravityengine.gravity.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * NeoForge 21.1.249 LivingEntity.travel Elytra branch rewritten into
 * a gravity-relative reference space.
 *
 * <p>{@code up} owns the aerodynamic reference frame. The exact sampled
 * acceleration vector remains the physical force and is never reconstructed
 * from the reference orientation.</p>
 */
public final class ElytraAerodynamics {
    private ElytraAerodynamics() {}

    /**
     * Conservative translation bound before pose/frame acceptance.
     *
     * <p>Lift transfer, pull-up and alignment together are bounded by
     * {@code 3 * (|v| + |a|)}; drag cannot enlarge the request.</p>
     */
    public static double maximumRequestMagnitude(
            Vec3 velocity,
            Vec3 accelerationWorld
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(accelerationWorld, "accelerationWorld");

        return 3.0D * (
                velocity.length()
                        + accelerationWorld.length()
        );
    }

    public static double tangentSpeed(
            Vec3 velocity,
            Vec3 up
    ) {
        return velocity.subtract(
                up.scale(velocity.dot(up))
        ).length();
    }

    public static Vec3 step(
            Vec3 velocity,
            Vec3 look,
            Vec3 up,
            Vec3 accelerationWorld,
            boolean slowFalling
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(look, "look");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(
                accelerationWorld,
                "accelerationWorld"
        );

        double gravityMagnitude = accelerationWorld.length();
        Vec3 effectiveAcceleration = accelerationWorld;

        /*
         * Vanilla Slow Falling caps d0 before both gravity and the
         * gravity-scaled Elytra lift term are evaluated.
         *
         * Preserve the exact sampled acceleration direction while reducing
         * only its magnitude.
         */
        if (velocity.dot(up) <= 0.0D
                && slowFalling
                && gravityMagnitude > 0.01D) {
            effectiveAcceleration =
                    accelerationWorld.scale(
                            0.01D / gravityMagnitude
                    );
            gravityMagnitude = 0.01D;
        }

        Vec3 tangentLook = look.subtract(
                up.scale(look.dot(up))
        );

        double lookTangentMagnitude =
                tangentLook.length();

        double incomingTangentSpeed =
                tangentSpeed(velocity, up);

        /*
         * Vanilla pitch sign:
         * positive = looking down,
         * negative = looking up.
         */
        float pitchRadians =
                (float) Math.atan2(
                        -look.dot(up),
                        lookTangentMagnitude
                );

        double lift = Math.cos((double) pitchRadians);
        lift = lift * lift
                * Math.min(
                1.0D,
                look.length() / 0.4D
        );

        /*
         * Vanilla:
         *
         * vy += gravity * (-1 + lift * 0.75)
         *
         * Rewrite:
         *
         * exact physical acceleration
         * +
         * aerodynamic lift along reference-space up.
         */
        Vec3 result = velocity
                .add(effectiveAcceleration)
                .add(up.scale(
                        gravityMagnitude
                                * lift
                                * 0.75D
                ));

        double vertical = result.dot(up);

        if (vertical < 0.0D
                && lookTangentMagnitude > 0.0D) {
            double transfer =
                    vertical * -0.1D * lift;

            result = result
                    .add(tangentLook.scale(
                            transfer
                                    / lookTangentMagnitude
                    ))
                    .add(up.scale(transfer));
        }

        if (pitchRadians < 0.0F
                && lookTangentMagnitude > 0.0D) {
            double transfer =
                    incomingTangentSpeed
                            * (double) (-Mth.sin(pitchRadians))
                            * 0.04D;

            result = result
                    .add(tangentLook.scale(
                            -transfer
                                    / lookTangentMagnitude
                    ))
                    .add(up.scale(
                            transfer * 3.2D
                    ));
        }

        if (lookTangentMagnitude > 0.0D) {
            Vec3 tangentVelocity =
                    result.subtract(
                            up.scale(result.dot(up))
                    );

            result = result.add(
                    tangentLook.scale(
                                    incomingTangentSpeed
                                            / lookTangentMagnitude
                            )
                            .subtract(tangentVelocity)
                            .scale(0.1D)
            );
        }

        Vec3 verticalResult =
                up.scale(result.dot(up));

        return result
                .subtract(verticalResult)
                .scale((double) 0.99F)
                .add(
                        verticalResult.scale(
                                (double) 0.98F
                        )
                );
    }
}
