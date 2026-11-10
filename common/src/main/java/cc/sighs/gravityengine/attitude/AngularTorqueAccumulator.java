package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One solver step's ephemeral world-space torque accumulation.
 *
 * <p>Attitude policies contribute torque here; they never write {@code q},
 * {@code L_world} or the derived {@code omega_world}. Contributions keep their
 * source so diagnostics and ownership tests can tell player roll, heading
 * control, global damping, explicit braking and numerical protection
 * apart.</p>
 *
 * <p>Units are {@code inertia-unit * rad / s^2}. The accumulator is never
 * persisted and never becomes durable state: {@link AngularDynamicsSolver}
 * consumes the net torque for exactly one substep.</p>
 */
public final class AngularTorqueAccumulator {

    /** Named provenance of one world-space torque contribution. */
    public enum TorqueSource {
        /** Held player roll input acting about the physical flight forward axis. */
        PLAYER_ROLL,
        /** Heading controller proportional and derivative (heading-only) torque. */
        HEADING_CONTROL,
        /** Configurable game angular damping, a generic control model. */
        GLOBAL_DAMPING,
        /** Explicit braking or stabilizer policy, never implied by zero input. */
        EXPLICIT_BRAKE_OR_STABILIZER,
        /**
         * Emergency protection torque. Ordinary control must never rely on it,
         * and a trigger may break momentum conservation.
         */
        NUMERICAL_SAFETY
    }

    private final EnumMap<TorqueSource, Vec3d> contributions =
            new EnumMap<>(TorqueSource.class);

    public void add(TorqueSource source, Vec3d torqueWorld) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(torqueWorld, "torqueWorld");
        if (!torqueWorld.isFinite()
                || !Double.isFinite(torqueWorld.lengthSquared())) {
            throw new IllegalArgumentException(
                    source + " torque must be finite: " + torqueWorld);
        }
        /*
         * Zero-valued contributions are dropped so a policy that analytically
         * produced no torque (including a negative-zero rounding residue from
         * a rejected axial component) leaves the accumulator empty.
         */
        if (torqueWorld.x() == 0.0D
                && torqueWorld.y() == 0.0D
                && torqueWorld.z() == 0.0D) {
            return;
        }
        contributions.merge(source, torqueWorld, Vec3d::add);
    }

    public Vec3d contribution(TorqueSource source) {
        return contributions.getOrDefault(source, Vec3d.ZERO);
    }

    public boolean isEmpty() {
        return contributions.isEmpty();
    }

    /** Net world-space torque for this substep. */
    public Vec3d netTorqueWorld() {
        Vec3d net = Vec3d.ZERO;
        for (Vec3d contribution : contributions.values()) {
            net = net.add(contribution);
        }
        return net;
    }

    public Map<TorqueSource, Vec3d> contributions() {
        return Collections.unmodifiableMap(new EnumMap<>(contributions));
    }
}
