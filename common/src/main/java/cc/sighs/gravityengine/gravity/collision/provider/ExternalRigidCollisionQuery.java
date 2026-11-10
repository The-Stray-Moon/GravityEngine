package cc.sighs.gravityengine.gravity.collision.provider;

import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.Objects;

/**
 * Loader-neutral discovery request for external rigid collision providers.
 *
 * <p>The query contains only engine values. A target adapter may hold its own
 * platform state externally, but a provider never needs a thread-local
 * Minecraft entity merely to know which region or simulation interval to
 * search.</p>
 *
 * <p>Bounds select relevant geometry; they do not contain whole primitives.
 * Providers publish complete, stable primitives whose conservative swept
 * bounds intersect the discovery region. Publisher-owned geometry bounds and
 * material-point motion reach are separate validation contracts.</p>
 */
public record ExternalRigidCollisionQuery(
        Aabb3d staticBounds,
        Aabb3d dynamicBounds,
        KinematicStepContext time
) {
    public ExternalRigidCollisionQuery {
        Objects.requireNonNull(staticBounds, "staticBounds");
        Objects.requireNonNull(dynamicBounds, "dynamicBounds");
        Objects.requireNonNull(time, "time");
    }
}
