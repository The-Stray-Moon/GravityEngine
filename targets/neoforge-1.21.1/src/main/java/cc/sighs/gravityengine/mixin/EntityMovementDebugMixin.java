package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;


@Mixin(Entity.class)
public abstract class EntityMovementDebugMixin {
    /** Carry the same observation across the actual call, including nested
     * calls and exceptional returns. Entity.move owns closing the span. */
    @WrapMethod(method = "collide")
    private Vec3 gravityengine$observeVanillaCollision(Vec3 movement, Operation<Vec3> original) {
        var entity = (Entity) (Object) this;
        var span = GravityDebugLog.shouldLogMovement(entity)
                && !EntityMovementIntegration.engineOwnsActiveMovement(entity)
                ? MovementCollisionDiagnostics.capture(entity) : null;
        if (span != null) MovementCollisionDiagnostics.vanillaInput(entity, span, movement);
        Vec3 result = original.call(movement);
        if (span != null) MovementCollisionDiagnostics.vanillaResult(entity, span, result);
        return result;
    }
}
