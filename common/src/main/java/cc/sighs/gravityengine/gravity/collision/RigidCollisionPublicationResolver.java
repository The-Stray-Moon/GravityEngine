package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned reacquisition boundary from stable rigid identity to the
 * current immutable publication.
 *
 * <p>Spatial discovery is separate: a target/provider discovers obstacles
 * while capturing the operation scene, then registers ownership for the
 * source ids it published. Later operations can ask the same source for the
 * exact publication without a broad world scan.</p>
 */
@FunctionalInterface
public interface RigidCollisionPublicationResolver {
    /**
     * Resolves the current publication for {@code identity}, or empty when the
     * source/primitive is no longer available or has lost continuity.
     */
    Optional<DynamicCollisionObstacleSnapshot> resolve(
            RigidObstacleIdentity identity,
            KinematicStepContext time
    );

    /**
     * Whether the resolver can still answer for the registered source.
     *
     * <p>Target adapters that retain an entity or world object should expose
     * only weak ownership through their resolver and return {@code false}
     * once that owner is gone.</p>
     */
    default boolean isLive() {
        return true;
    }

    /**
     * Whether this resolver and {@code other} represent the same logical
     * publication owner.
     *
     * <p>The registry may receive a fresh resolver wrapper on every scene
     * capture. Wrapper object identity therefore is not, by itself, a stable
     * ownership contract. Target adapters whose wrappers are recreated must
     * override this method using an owner identity that is stable for the
     * lifetime of the underlying object.</p>
     *
     * <p>The default is deliberately conservative and accepts only the same
     * resolver instance. Implementations should keep this relation symmetric
     * and side-effect free.</p>
     */
    default boolean sameOwner(
            RigidCollisionPublicationResolver other
    ) {
        Objects.requireNonNull(other, "other");
        return this == other;
    }
}
