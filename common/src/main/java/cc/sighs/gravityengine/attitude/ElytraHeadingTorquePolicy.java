package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;

/**
 * Heading constraint: the physical flight forward direction follows the
 * resolved flight intent.
 *
 * <p>This policy constrains one direction only. It never constructs a target
 * quaternion from {@code desiredForward + worldUp} and never completes roll.
 * A direction error uses the shortest-arc rotation vector; the exactly
 * antiparallel case resolves through the retained body-derived flight dorsal
 * axis, so no world-up or gravity-up preference can leak in.</p>
 *
 * <p>Derivative damping acts only on the angular-velocity component that
 * changes the flight forward direction. The axial (roll) component is
 * rejected explicitly, so a pure axial momentum cannot be cancelled by the
 * heading stabilizer.</p>
 */
public final class ElytraHeadingTorquePolicy implements AttitudeTorquePolicy {
    public static final ElytraHeadingTorquePolicy INSTANCE =
            new ElytraHeadingTorquePolicy();

    private ElytraHeadingTorquePolicy() {}

    @Override
    public String name() {
        return "ELYTRA_HEADING_TORQUE";
    }

    @Override
    public void accumulate(
            AngularTorqueAccumulator accumulator,
            AttitudeTorqueContext context
    ) {
        Vec3d forward =
                AttitudeSpaceTransform.elytraForwardWorld(
                        context.worldFromBody());
        Vec3d dorsal =
                AttitudeSpaceTransform.elytraDorsalWorld(
                        context.worldFromBody());
        double epsilon = context.config().vectorEpsilon();

        Vec3d error = BodyAttitudeMath.directionRotationError(
                forward,
                context.flightIntent().desiredForwardWorld(),
                dorsal,
                epsilon
        );

        double gain = context.config().elytraHeadingTorqueGain()
                * context.controlProfile().flightAlignmentWeight();
        accumulator.add(
                AngularTorqueAccumulator.TorqueSource.HEADING_CONTROL,
                error.multiply(gain)
        );

        double damping = context.config().elytraHeadingDamping();
        if (damping > 0.0D) {
            Vec3d omega = context.angularVelocityWorld();
            Vec3d omegaHeading =
                    omega.subtract(forward.multiply(omega.dot(forward)));
            /*
             * tau = I * alpha with alpha = -k * omega_heading. Rejecting the
             * axial component keeps a pure roll from being damped here.
             */
            accumulator.add(
                    AngularTorqueAccumulator.TorqueSource.HEADING_CONTROL,
                    omegaHeading.multiply(
                            -damping * context.inertia().isotropic()
                    )
            );
        }
    }
}
