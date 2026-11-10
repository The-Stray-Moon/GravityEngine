package cc.sighs.gravityengine.attitude;

/**
 * Configurable game angular damping as an explicit, generic torque
 * contribution.
 *
 * <pre>
 * tau_damping_world = -damping * L_world
 * </pre>
 *
 * <p>The coefficient is a game-control parameter in {@code 1/s}; with the
 * isotropic model this is equivalent to {@code tau = -I * k * omega}. It is
 * deliberately not named aerodynamic drag, is applied exactly once, and has a
 * strict zero-damping configuration under which a torque-free step preserves
 * {@code L_world} exactly. This policy owns no heading or roll preference.</p>
 */
public final class GlobalAngularDampingTorquePolicy
        implements AttitudeTorquePolicy {
    public static final GlobalAngularDampingTorquePolicy INSTANCE =
            new GlobalAngularDampingTorquePolicy();

    private GlobalAngularDampingTorquePolicy() {}

    @Override
    public String name() {
        return "GLOBAL_ANGULAR_DAMPING";
    }

    @Override
    public void accumulate(
            AngularTorqueAccumulator accumulator,
            AttitudeTorqueContext context
    ) {
        double damping = context.config().elytraAngularDamping();
        if (!(damping > 0.0D)) {
            return;
        }
        accumulator.add(
                AngularTorqueAccumulator.TorqueSource.GLOBAL_DAMPING,
                context.angularMomentumWorld().multiply(-damping)
        );
    }
}
