package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Neutral parent-call carrier; it contains no Sable collision/support evidence. */
public final class SableCollisionOwnership {
    private SableCollisionOwnership() {}
    public static final class EngineCollisionInfo extends SubLevelEntityCollision.CollisionInfo {}

    public static SubLevelEntityCollision.CollisionInfo parentOnly(Entity actor, Vec3 movement) {
        var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
        if (runtime.collisionOperation() == null
                || EntityMovementIntegration.activeMovementCollisionRoute(actor)
                        != GravityCollisionRoute.EXACT_BODY) return null;
        var extension = (EntityMovementExtension)actor;
        extension.sable$setTrackingSubLevel(null);
        extension.sable$setLastTrackingSubLevelID(null);
        if (actor instanceof LivingEntityMovementExtension living) {
            var inherited = living.sable$getInheritedVelocity();
            actor.setDeltaMovement(actor.getDeltaMovement().add(inherited.x, inherited.y, inherited.z));
            inherited.zero();
        }
        var info = new EngineCollisionInfo();
        info.motion = movement;
        return info;
    }
}
