package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaShieldBridge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shield/facing directional operands.
 *
 * <p>21.1.249 {@code LivingEntity.isDamageSourceBlocked} flattens the
 * defender facing to pitch-zero yaw and the incoming source direction into
 * world XZ before the front/back test.  For GravityEngine semantic actors the
 * defender facing is semantic view projected to its reference tangent plane,
 * and the incoming direction is projected to the same reference plane.
 * Vanilla retains shield policy, damage-source rules and cooldown/callback
 * behavior.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityShieldMixin {
    @WrapOperation(
            method = "isDamageSourceBlocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/damagesource/DamageSource;"
                            + "getSourcePosition()Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$captureShieldSourcePosition(
            DamageSource source,
            Operation<Vec3> original,
            @Share("gravityengineShieldSource")
            LocalRef<Vec3> sourceRef
    ) {
        Vec3 position = original.call(source);
        sourceRef.set(position);
        return position;
    }

    @WrapOperation(
            method = "isDamageSourceBlocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$shieldFacing(
            LivingEntity defender,
            float xRot,
            float yRot,
            Operation<Vec3> original,
            @Share("gravityengineShieldDefender")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        // The original operand is calculateViewVector(0, getYHeadRot()),
        // not entity yRot. ACTIVE attitude still owns its semantic head joint.
        VanillaActorSnapshot actor = VanillaActorBridge.capture(defender, yRot, xRot);
        actorRef.set(actor);
        if (!(actor.transformedLook() || actor.nonDefaultReferenceFrame())) {
            return original.call(defender, xRot, yRot);
        }
        return VanillaShieldBridge.shieldFacingDirection(actor);
    }

    @WrapOperation(
            method = "isDamageSourceBlocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;"
                            + "normalize()Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$shieldIncoming(
            Vec3 flattened,
            Operation<Vec3> original,
            @Share("gravityengineShieldSource")
            LocalRef<Vec3> sourceRef,
            @Share("gravityengineShieldDefender")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        Vec3 source = sourceRef.get();
        VanillaActorSnapshot actor = actorRef.get();
        if (!(actor.transformedLook() || actor.nonDefaultReferenceFrame())
                || source == null) {
            return original.call(flattened);
        }
        Vec3 incoming = VanillaShieldBridge.shieldIncomingDirection(
                actor, source);
        return incoming == null ? Vec3.ZERO : incoming;
    }
}
