package cc.sighs.gravityengine.attitude;

/**
 * One named attitude policy that contributes world-space torque for a solver
 * substep.
 *
 * <p>Policies are the only seam shared by Elytra and future flying entities.
 * They do not share propulsion, lift, AI, navigation, mass distribution or an
 * external rigid-body solver merely because they share this interface, and
 * they never write {@code q}, {@code L_world} or {@code omega_world}.</p>
 */
public interface AttitudeTorquePolicy {

    /** Stable diagnostic name of this contribution. */
    String name();

    void accumulate(
            AngularTorqueAccumulator accumulator,
            AttitudeTorqueContext context
    );
}
