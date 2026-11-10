package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBlockResponse;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** 21.1.249: movement emission retains native climb, sound, event and distance policy. */
@Mixin(Entity.class)
public abstract class EntityMovementEmissionMixin {
    @WrapOperation(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/phys/Vec3;x:D"),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D")), require = 1)
    private double gravityengine$emissionX(Vec3 movement, Operation<Double> original) {
        return VanillaBlockResponse.local((Entity) (Object) this, movement).x;
    }
    @WrapOperation(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/phys/Vec3;y:D"),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D")), require = 1)
    private double gravityengine$emissionY(Vec3 movement, Operation<Double> original) {
        return VanillaBlockResponse.local((Entity) (Object) this, movement).y;
    }
    @WrapOperation(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/phys/Vec3;z:D"),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D")), require = 1)
    private double gravityengine$emissionZ(Vec3 movement, Operation<Double> original) {
        return VanillaBlockResponse.local((Entity) (Object) this, movement).z;
    }
    @WrapOperation(method = "vibrationAndSoundEffectsFromBlock", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/phys/Vec3;y:D"), require = 1)
    private double gravityengine$stepVertical(Vec3 movement, Operation<Double> original) {
        return VanillaBlockResponse.local((Entity) (Object) this, movement).y;
    }
}
