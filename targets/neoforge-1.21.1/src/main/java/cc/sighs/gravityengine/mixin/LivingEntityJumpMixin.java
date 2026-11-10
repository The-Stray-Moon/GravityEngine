package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.LivingGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.collision.GravityJumpSupportQuery;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPropulsionBridge;
import cc.sighs.gravityengine.gravity.movement.CharacterLocomotionTechnique;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Current-contact ground eligibility; native input, cooldown, fluids, power and event order. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
    // NeoForge 21.1.249: three fluid-routing reads precede the fourth onGround
    // read (bytecode offset 489), which alone gates jumpFromGround/noJumpDelay.
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;onGround()Z", ordinal = 3),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;getFluidJumpThreshold()D"),
                    to = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/LivingEntity;jumpFromGround()V")),
            require = 1, allow = 1)
    private boolean gravityengine$currentJumpSupport(LivingEntity entity, Operation<Boolean> original) {
        if (!GravityInfluencePolicy.usesCustomLocomotion(entity)
                || !GravityInfluencePolicy.usesCustomBody(entity)) return original.call(entity);
        return GravityJumpSupportQuery.canJump(entity);
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true, require = 1)
    private void gravityengine$jumpSnapshot(CallbackInfo ci,
            @Share("jump") LocalRef<VanillaPropulsionBridge.Jump> shared) {
        var entity = (LivingEntity) (Object) this;
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                && cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomLocomotion(entity)) {
            ci.cancel();
            return;
        }
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
        if (shared.get() != null) LivingGravityIntegration.commitJump(entity, shared.get());
        original.call(entity);
    }
}
