package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.PassiveGravityCollisionIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AABB trajectory support only; landing/placement remains vanilla world-Y. */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;applyGravity()V"),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            FallingBlockEntity fallingBlock,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(fallingBlock, 1.0D)) {
            original.call(fallingBlock);
        }
    }

    /** Arbitrary-surface block placement remains an explicit blocker. */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;onGround()Z"),
            require = 1
    )
    private boolean gravityengine$blockArbitrarySurfacePlacement(
            FallingBlockEntity entity,
            Operation<Boolean> original
    ) {
        return PassiveGravityCollisionIntegration.hasDefaultDownPassiveFrame(entity)
                && original.call(entity);
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"),
            require = 1
    )
    private Vec3 gravityengine$gravityLocalBounce(
            Vec3 velocity, double x, double y, double z,
            Operation<Vec3> original
    ) {
        FallingBlockEntity entity = (FallingBlockEntity) (Object) this;
        if (!PassiveGravityCollisionIntegration.hasSupportedPassiveContact(entity)) {
            return original.call(velocity, x, y, z);
        }
        return PassiveGravityCollisionIntegration.gravityLocalScale(
                entity, velocity, x, y
        );
    }
}
