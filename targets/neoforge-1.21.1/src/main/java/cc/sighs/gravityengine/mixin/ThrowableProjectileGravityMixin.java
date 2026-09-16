package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.21.1 ThrowableProjectile tick: wrap its single post-drag gravity call. */
@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;applyGravity()V"
            ),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            ThrowableProjectile projectile,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(projectile, 1.0D)) {
            original.call(projectile);
        }
    }
}
