package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;

/**
 * Held player roll input as a sustained world-space torque about the physical
 * flight forward axis.
 *
 * <pre>
 * tau_roll_world = rollInput * rollTorque * elytraForwardWorld(q)
 * </pre>
 *
 * <p>{@code rollInput} is the held-key axis in {@code [-1, 1]} sampled from
 * held key state, never from a key-repeat event or a render frame. Opposing
 * keys already collapse to zero net input at capture. Releasing input removes
 * this contribution only: there is no target roll angle, no target roll rate,
 * no release-time momentum reset and no implicit braking.</p>
 *
 * <p>The forward axis comes from the current solver sample's physical body
 * attitude through the single {@code AttitudeSpaceTransform} flight-axis
 * authority. Render-interpolated attitudes, camera attitudes, fixed world axes
 * and gravity up/down are explicitly not acceptable sources.</p>
 */
public final class ElytraPlayerRollTorquePolicy implements AttitudeTorquePolicy {
    public static final ElytraPlayerRollTorquePolicy INSTANCE =
            new ElytraPlayerRollTorquePolicy();

    private ElytraPlayerRollTorquePolicy() {}

    @Override
    public String name() {
        return "PLAYER_ROLL_TORQUE";
    }

    @Override
    public void accumulate(
            AngularTorqueAccumulator accumulator,
            AttitudeTorqueContext context
    ) {
        double rollInput = context.input().rollAxis()
                * context.controlProfile().userControl().roll();
        if (rollInput == 0.0D) {
            return;
        }
        Vec3d forward =
                AttitudeSpaceTransform.elytraForwardWorld(
                        context.worldFromBody());
        accumulator.add(
                AngularTorqueAccumulator.TorqueSource.PLAYER_ROLL,
                forward.multiply(rollInput * context.config().elytraRollTorque())
        );
    }
}
