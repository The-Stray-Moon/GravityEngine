package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBlockResponse;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 21.1.249: preserve native careful-stepping, vertical threshold and damping coefficient. */
@Mixin(SlimeBlock.class)
public abstract class SlimeStepResponseMixin {
    @WrapOperation(method = "stepOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"), require = 2)
    private Vec3 gravityengine$local(Entity entity, Operation<Vec3> original) {
        return VanillaBlockResponse.local(entity, original.call(entity));
    }

    @WrapOperation(method = "stepOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void gravityengine$world(Entity entity, Vec3 local, Operation<Void> original) {
        original.call(entity, VanillaBlockResponse.world(entity, local));
    }
}
