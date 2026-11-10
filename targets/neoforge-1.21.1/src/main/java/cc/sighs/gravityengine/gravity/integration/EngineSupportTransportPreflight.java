package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.PersistentSupportState;
import cc.sighs.gravityengine.gravity.collision.RigidSupportTransportPreflight;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;

import java.util.Optional;

/**
 * Entity-free preflight core for engine support transport.
 *
 * <p>The Minecraft adapter supplies the level lookup scope; tests can supply
 * an isolated account scope. This class never assumes source ids are entity
 * ids.</p>
 */
final class EngineSupportTransportPreflight {
    private EngineSupportTransportPreflight() {}

    static Optional<SupportTransport> preflight(
            PersistentSupportState support,
            Object scope,
            long gameTick,
            double intervalTicks
    ) {
        return RigidSupportTransportPreflight.preflight(
                support,
                scope,
                new KinematicStepContext(
                        gameTick,
                        intervalTicks,
                        0L
                )
        );
    }
}
