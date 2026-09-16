package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.AimOperands;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;


/** 21.1.249 Drowned: target one-third body height; X/Z source is drowned feet, Y source is trident. No velocity lead. Vanilla computes XZ distance * (double)0.2F and owns speed/inaccuracy. */
@Mixin(Drowned.class)
public abstract class DrownedRangedMixin {
    private static final String METHOD = "performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V";

    @Inject(
            method = METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/"
                                    + "LivingEntity;getX()D",
                    ordinal = 0
            ),
            require = 1
    )
    private void gravityengine$captureAim(
            LivingEntity target,
            float distanceFactor,
            CallbackInfo ci,
            @Local ThrownTrident projectile,
            @Share("gravityengineAim")
            LocalRef<AimOperands> ref
    ) {
        VanillaActorSnapshot shooter =
                VanillaActorBridge.capture(
                        (Drowned) (Object) this
                );

        Vec3 point =
                VanillaProjectileBridge.bodyHeightPoint(
                        target,
                        1.0D / 3.0D
                );

        ref.set(
                new VanillaProjectileBridge.AimOperands(
                        shooter.referenceFrame(),
                        VanillaProjectileBridge.referenceTangentCarrier(
                                shooter.referenceFrame(),
                                point.subtract(
                                        shooter.feet()
                                )
                        ),
                        VanillaProjectileBridge.referenceVerticalComponent(
                                shooter.referenceFrame(),
                                point.subtract(
                                        projectile.position()
                                )
                        ),
                        shooter.customBody()
                                || cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(target)
                                || shooter.nonDefaultReferenceFrame()
                )
        );
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
            target = "Lnet/minecraft/world/entity/projectile/ThrownTrident;shoot(DDDFF)V"), require = 1)
    private void gravityengine$worldShot(ThrownTrident projectile, double x, double y, double z,
            float velocity, float inaccuracy, Operation<Void> original,
            @Share("gravityengineAim") LocalRef<AimOperands> ref) {
        AimOperands operands = ref.get();
        if (operands.translated()) {
            Vec3 world = VanillaProjectileBridge.worldShot(operands.frame(), x, y, z);
            original.call(projectile, world.x, world.y, world.z, velocity, inaccuracy);
        } else original.call(projectile, x, y, z, velocity, inaccuracy);
    }
}
