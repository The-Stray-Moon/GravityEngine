package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 21.1.249 AIM_DEFLECT's lambda$static$2 owns one getLookAngle operand.
 * Keep its null check, normalization, unit-speed commit and hasImpulse ordering.
 * No other deflection policy or global look accessor is changed.
 */
@Mixin(ProjectileDeflection.class)
public interface ProjectileDeflectionMixin {
    @WrapOperation(method = "lambda$static$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private static Vec3 gravityengine$aimDeflection(Entity entity, Operation<Vec3> original) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(entity);
        return actor.transformedLook()
                ? actor.viewForward() : original.call(entity);
    }
}
