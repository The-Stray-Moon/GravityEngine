package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.PassiveGravityCollisionIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces only ItemEntity's 1.21.1 vanilla applyGravity call. Aging, fluids,
 * movement, drag, merging and pickup behavior remain in the vanilla tick.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/ItemEntity;applyGravity()V"
            ),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            ItemEntity entity,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(entity, 1.0D)) {
            original.call(entity);
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 0
            ),
            require = 1
    )
    private Vec3 gravityengine$gravityLocalDrag(
            Vec3 velocity, double x, double y, double z,
            Operation<Vec3> original
    ) {
        ItemEntity entity = (ItemEntity) (Object) this;
        if (!PassiveGravityCollisionIntegration.hasSupportedPassiveContact(entity)) {
            return original.call(velocity, x, y, z);
        }
        return PassiveGravityCollisionIntegration.gravityLocalScale(
                entity, velocity, x, y
        );
    }
}
