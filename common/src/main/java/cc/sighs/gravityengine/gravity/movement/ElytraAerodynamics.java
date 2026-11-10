package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Gravity-relative reference-space rewrite of the Vanilla living-entity
 * Elytra travel branch.
 *
 * <p>{@code up} owns the aerodynamic reference frame. The exact sampled
 * acceleration vector remains the physical force and is never reconstructed
 * from the reference orientation.</p>
 *
 * <p>This is pure mathematics: it consumes only GravityEngine-owned immutable
 * {@link Vec3d} values and standard-library trigonometry. Capturing the live
 * Minecraft elytra inputs (fall-flying state, slow falling, look direction,
 * delta movement) and committing the result back to the entity remain
 * target-side responsibilities.</p>
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
            Vec3d velocity,
            Vec3d accelerationWorld
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(accelerationWorld, "accelerationWorld");

        return 3.0D * (
                velocity.length()
                        + accelerationWorld.length()
        );
    }

    public static double tangentSpeed(
            Vec3d velocity,
            Vec3d up
    ) {
        return velocity.subtract(
                up.multiply(velocity.dot(up))
        ).length();
    }

    public static Vec3d step(
            Vec3d velocity,
            Vec3d look,
            Vec3d up,
            Vec3d accelerationWorld,
            boolean slowFalling
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(look, "look");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(accelerationWorld, "accelerationWorld");

        double gravityMagnitude = accelerationWorld.length();
        Vec3d effectiveAcceleration = accelerationWorld;

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
                    accelerationWorld.multiply(
                            0.01D / gravityMagnitude
                    );
            gravityMagnitude = 0.01D;
        }

        Vec3d tangentLook = look.subtract(
                up.multiply(look.dot(up))
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
        Vec3d result = velocity
                .add(effectiveAcceleration)
                .add(up.multiply(
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
                    .add(tangentLook.multiply(
                            transfer
                                    / lookTangentMagnitude
                    ))
                    .add(up.multiply(transfer));
        }

        if (pitchRadians < 0.0F
                && lookTangentMagnitude > 0.0D) {
            double transfer =
                    incomingTangentSpeed
                            * -Math.sin((double) pitchRadians)
                            * 0.04D;

            result = result
                    .add(tangentLook.multiply(
                            -transfer
                                    / lookTangentMagnitude
                    ))
                    .add(up.multiply(
                            transfer * 3.2D
                    ));
        }

        if (lookTangentMagnitude > 0.0D) {
            Vec3d tangentVelocity =
                    result.subtract(
                            up.multiply(result.dot(up))
                    );

            result = result.add(
                    tangentLook.multiply(
                                    incomingTangentSpeed
                                            / lookTangentMagnitude
                            )
                            .subtract(tangentVelocity)
                            .multiply(0.1D)
            );
        }

        Vec3d verticalResult =
                up.multiply(result.dot(up));

        return result
                .subtract(verticalResult)
                .multiply((double) 0.99F)
                .add(
                        verticalResult.multiply(
                                (double) 0.98F
                        )
                );
    }
}
