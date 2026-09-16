package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.21.1 AbstractArrow tick: wrap its single airborne post-drag gravity call. */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;applyGravity()V"
            ),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            AbstractArrow arrow,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(arrow, 1.0D)) {
            original.call(arrow);
        }
    }
}
