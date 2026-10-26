package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/** Optional velocity observation; discontinuity recording stays in EntityMixin. */
@Mixin(Entity.class)
public abstract class EntityVelocityDebugMixin {
    /** Trace every notable world-domain Vec3 velocity write without changing it. */
    @Inject(
            method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void gravityengine$traceNotableVecVelocityWrite(
            Vec3 incoming,
            CallbackInfo ci
    ) {
        if (!GravityDebugLog.shouldLog((Entity) (Object) this)) {
            return;
        }
        Entity entity = (Entity) (Object) this;
        if (EntityMovementIntegration.committedCollisionRoute(entity)
                == GravityCollisionRoute.VANILLA) return;

        GravityFrame frame = GravityFrameAccess.reliableFrame(entity);
        if (frame == null) return;
        Vec3 before = entity.getDeltaMovement();
        if (!GravityDebugLog.isNotableVerticalChange(frame, before, incoming)) {
            return;
        }

        cc.sighs.gravityengine.api.math.Vec3d beforeLocal =
                frame.worldToLocal(MinecraftMathAdapter.toVec3d(before));
        cc.sighs.gravityengine.api.math.Vec3d incomingLocal =
                frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(incoming));
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        GravityMoveResult result = runtime.currentMoveResult();
        GravityDebugLog.log(
                entity,
                "velocity-set-vec-notable",
                "beforeWorld=%s beforeLocal=%s incomingWorld=%s incomingLocal=%s "
                        + "deltaLocal=%s onGround=%s horizontalCollision=%s "
                        + "verticalCollision=%s verticalCollisionBelow=%s "
                        + "runtimeInMove=%s runtimeApplyingGeometry=%s %s %s "
                        + "caller=%s",
                GravityDebugLog.vec(before),
                GravityDebugLog.vec(beforeLocal),
                GravityDebugLog.vec(incoming),
                GravityDebugLog.vec(incomingLocal),
                GravityDebugLog.vec(incomingLocal.subtract(beforeLocal)),
                entity.onGround(),
                entity.horizontalCollision,
                entity.verticalCollision,
                entity.verticalCollisionBelow,
                runtime.isInMove(),
                runtime.isApplyingGeometry(),
                GravityDebugLog.formatMoveResult(result),
                GravityDebugLog.verticalChangeFlags(beforeLocal, incomingLocal),
                GravityDebugLog.callerStack()
        );
    }

}
