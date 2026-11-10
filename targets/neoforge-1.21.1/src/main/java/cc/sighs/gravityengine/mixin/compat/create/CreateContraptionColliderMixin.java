package cc.sighs.gravityengine.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;
import java.util.function.Predicate;

/** Remove only the competing native character collision/carry contribution.
 * Contraption controllers, passengers and Sable's rigid pipeline still run. */
@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class CreateContraptionColliderMixin {
    @WrapOperation(method = "collideEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"), require = 1)
    private static List<Entity> gravityengine$characterOwner(Level level, Class<Entity> type, AABB bounds,
            Predicate<Entity> predicate, Operation<List<Entity>> original,
            @Local(argsOnly = true) AbstractContraptionEntity contraption) {
        if (!cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider.publishes(contraption))
            return original.call(level, type, bounds, predicate);
        return original.call(level, type, bounds, predicate.and(actor ->
                !cc.sighs.gravityengine.gravity.integration.MovementModeIntegration.allowsExternalCollision(actor)
                        && !cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(actor)));
    }
}
