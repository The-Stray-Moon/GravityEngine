package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.LivingGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPropulsionBridge;
import cc.sighs.gravityengine.gravity.movement.CharacterLocomotionTechnique;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native jump gate, power threshold, sprint policy and event order; spatial operands only. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true, require = 1)
    private void gravityengine$jumpSnapshot(CallbackInfo ci,
            @Share("jump") LocalRef<VanillaPropulsionBridge.Jump> shared) {
        var entity = (LivingEntity) (Object) this;
        var jump = VanillaPropulsionBridge.prepareJump(entity);
        if (jump == null) return;
        if (jump.technique() == CharacterLocomotionTechnique.FREE_3D) {
            ci.cancel();
        } else if (jump.technique() == CharacterLocomotionTechnique.GROUND_AIR) {
            shared.set(jump);
        }
    }

    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"), require = 1)
    private void gravityengine$jumpVelocity(LivingEntity entity, double x, double power, double z,
            Operation<Void> original, @Share("jump") LocalRef<VanillaPropulsionBridge.Jump> shared) {
        if (shared.get() == null) {
            original.call(entity, x, power, z);
            return;
        }
        Vec3 world = VanillaPropulsionBridge.jumpVelocity(shared.get(), power);
        original.call(entity, world.x, world.y, world.z);
    }

    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void gravityengine$sprintImpulse(LivingEntity entity, Vec3 impulse, Operation<Void> original,
            @Share("jump") LocalRef<VanillaPropulsionBridge.Jump> shared) {
        if (shared.get() != null) impulse = VanillaPropulsionBridge.sprintJumpImpulse(shared.get(), impulse);
        original.call(entity, impulse);
    }

    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingJump(Lnet/minecraft/world/entity/LivingEntity;)V"), require = 1)
    private void gravityengine$acceptedJump(LivingEntity entity, Operation<Void> original,
            @Share("jump") LocalRef<VanillaPropulsionBridge.Jump> shared) {
        if (shared.get() != null) LivingGravityIntegration.commitJump(entity, shared.get().before());
        original.call(entity);
    }
}
