package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.AimOperands;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 21.1.249 Witch: all source components use witch feet, NOT potion position.
 * Target is eye minus 1.1F without clamping. Only X/Z have target motion lead:
 * translate that as shooter-reference tangent lead, never add reference vertical
 * velocity. Vanilla computes tangent distance * 0.2D; the same distance still
 * drives potion choice, and all potion/damage/random/launch policy stays Vanilla.
 */
@Mixin(Witch.class)
public abstract class WitchRangedMixin {
    private static final String METHOD = "performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V";

    @Inject(method = METHOD, at = @At("HEAD"), require = 1)
    private void gravityengine$captureAim(LivingEntity target, float distanceFactor, CallbackInfo ci,
            @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        VanillaActorSnapshot shooter = VanillaActorBridge.capture((Witch) (Object) this);
        VanillaActorSnapshot targetActor = VanillaActorBridge.capture(target);
        Vec3 point = VanillaProjectileBridge.actorEyeBelow(targetActor, (double) 1.1F);
        ref.set(new VanillaProjectileBridge.AimOperands(shooter.referenceFrame(),
                VanillaProjectileBridge.referenceTangentCarrier(shooter.referenceFrame(),
                        point.subtract(shooter.feet()).add(target.getDeltaMovement())),
                VanillaProjectileBridge.referenceVerticalComponent(shooter.referenceFrame(), point.subtract(shooter.feet())),
                shooter.customBody() || targetActor.customBody() || shooter.nonDefaultReferenceFrame()));
    }

    // Exact STORE ordinals are the original method's double-valued aim locals.
    @ModifyVariable(method = METHOD, at = @At("STORE"), ordinal = 0, require = 1)
    private double gravityengine$aimX(double vanilla, @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        return ref.get().translated() ? ref.get().tangentCarrier().x : vanilla;
    }

    @ModifyVariable(method = METHOD, at = @At("STORE"), ordinal = 1, require = 1)
    private double gravityengine$aimY(double vanilla, @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        return ref.get().translated() ? ref.get().vertical() : vanilla;
    }

    @ModifyVariable(method = METHOD, at = @At("STORE"), ordinal = 2, require = 1)
    private double gravityengine$aimZ(double vanilla, @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        return ref.get().translated() ? ref.get().tangentCarrier().z : vanilla;
    }

    @WrapOperation(method = METHOD, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/ThrownPotion;shoot(DDDFF)V"), require = 1)
    private void gravityengine$worldShot(ThrownPotion projectile, double x, double y, double z,
            float velocity, float inaccuracy, Operation<Void> original,
            @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        AimOperands operands = ref.get();
        if (operands.translated()) {
            Vec3 world = VanillaProjectileBridge.worldShot(operands.frame(), x, y, z);
            original.call(projectile, world.x, world.y, world.z, velocity, inaccuracy);
        } else original.call(projectile, x, y, z, velocity, inaccuracy);
    }
}
