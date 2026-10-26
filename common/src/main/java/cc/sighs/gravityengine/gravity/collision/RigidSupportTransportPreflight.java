package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned support preflight before an operation collision scene exists.
 *
 * <p>This resolves persistent support through the same rigid publication
 * registry used by scene capture. The later captured scene is still the
 * authoritative validation; the preflight only provides the bounded transport
 * displacement needed to size the capture domain.</p>
 */
public final class RigidSupportTransportPreflight {
    private RigidSupportTransportPreflight() {}

    public static Optional<SupportTransport> preflight(
            PersistentSupportState support,
            Object scope,
            KinematicStepContext time
    ) {
        Objects.requireNonNull(time, "time");
        if (support == null) {
            return Optional.empty();
        }
        if (support.staticSupport()) {
            return Optional.of(
                    SupportTransport.resting(
                            time.intervalTicks(),
                            0L,
                            time.gameTick()
                    )
            );
        }

        RigidObstacleIdentity identity =
                RigidObstacleIdentity.of(
                        support.identity()
                );

        return RigidCollisionPublicationRegistry.resolve(
                        scope,
                        identity,
                        time
                )
                .flatMap(publication ->
                        SupportTransportResolver.resolve(
                                support,
                                publication,
                                time.intervalTicks(),
                                time.gameTick()
                        )
                );
    }
}
