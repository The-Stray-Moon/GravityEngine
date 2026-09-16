package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.PassiveGravityCollisionIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AABB trajectory support only; attraction and resting remain vanilla. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ExperienceOrb;applyGravity()V"),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            ExperienceOrb orb,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(orb, 1.0D)) {
            original.call(orb);
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"),
            require = 2
    )
    private Vec3 gravityengine$gravityLocalDamping(
            Vec3 velocity, double x, double y, double z,
            Operation<Vec3> original
    ) {
        ExperienceOrb orb = (ExperienceOrb) (Object) this;
        if (!PassiveGravityCollisionIntegration.hasSupportedPassiveContact(orb)) {
            return original.call(velocity, x, y, z);
        }
        return PassiveGravityCollisionIntegration.gravityLocalScale(
                orb, velocity, x, y
        );
    }
}
