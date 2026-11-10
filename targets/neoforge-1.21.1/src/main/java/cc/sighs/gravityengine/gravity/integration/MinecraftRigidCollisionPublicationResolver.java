package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.CollisionSurfaceMotionProvider;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidCollisionPublicationResolver;
import cc.sighs.gravityengine.gravity.collision.RigidObstacleIdentity;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import net.minecraft.world.entity.Entity;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Target adapter from a native moving entity to the engine's generic rigid
 * publication contract.
 *
 * <p>The resolver retains the entity weakly. The registry owns the route from
 * engine source id to this resolver; the entity remains the live publication
 * owner and the common engine never sees a Minecraft type.</p>
 *
 * <p>A new resolver wrapper may be created on every collision-scene capture.
 * The entity UUID is therefore captured separately as a stable, non-owning
 * identity so the common registry can refresh the resolver without mistaking
 * the new wrapper for a second live owner.</p>
 */
public final class MinecraftRigidCollisionPublicationResolver
        implements RigidCollisionPublicationResolver {
    private final WeakReference<Entity> entity;
    private final UUID ownerId;

    public MinecraftRigidCollisionPublicationResolver(Entity entity) {
        Entity owner = Objects.requireNonNull(entity, "entity");
        this.entity = new WeakReference<>(owner);
        this.ownerId = owner.getUUID();
    }

    @Override
    public Optional<DynamicCollisionObstacleSnapshot> resolve(
            RigidObstacleIdentity identity,
            KinematicStepContext time
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(time, "time");

        Entity current = entity.get();
        if (current == null
                || current.isRemoved()
                || !(current instanceof CollisionSurfaceMotionProvider provider)) {
            return Optional.empty();
        }

        CollisionSurfaceMotionProvider.MotionSnapshot snapshot =
                provider.gravityengine$collisionSurfaceMotionSnapshot();
        if (snapshot == null) {
            return Optional.empty();
        }

        for (DynamicCollisionObstacleSnapshot primitive
                : snapshot.primitives()) {
            if (identity.matches(primitive)) {
                return Optional.of(primitive);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isLive() {
        Entity current = entity.get();
        return current != null && !current.isRemoved();
    }

    @Override
    public boolean sameOwner(
            RigidCollisionPublicationResolver other
    ) {
        if (this == other) {
            return true;
        }
        return other instanceof MinecraftRigidCollisionPublicationResolver resolver
                && ownerId.equals(resolver.ownerId);
    }
}
