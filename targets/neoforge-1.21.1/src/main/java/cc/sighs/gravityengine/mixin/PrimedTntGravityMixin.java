package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.PassiveGravityCollisionIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AABB trajectory support only; gravity-relative ground bounce is a blocker. */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/PrimedTnt;applyGravity()V"),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            PrimedTnt tnt,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(tnt, 1.0D)) {
            original.call(tnt);
        }
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
        PrimedTnt tnt = (PrimedTnt) (Object) this;
        if (!PassiveGravityCollisionIntegration.hasSupportedPassiveContact(tnt)) {
            return original.call(velocity, x, y, z);
        }
        return PassiveGravityCollisionIntegration.gravityLocalScale(
                tnt, velocity, x, y
        );
    }
}
