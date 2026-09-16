package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attached FireworkRocket gameplay-spatial bridge.
 *
 * <p>In the 1.21.1 fall-flying branch, the first
 * {@code LivingEntity.getLookAngle()} supplies the propulsion direction and
 * {@code getHandHoldingItemAngle()} supplies an anatomical attachment offset.
 * One actor snapshot feeds both operations. Vanilla retains boost constants,
 * velocity ordering, lifetime, explosion and entity-state policy.</p>
 *
 * <p>The hand offset is expressed relative to the physical body/anatomical
 * lower anchor; no render interpolation state participates.</p>
 *
 * <p>21.1.249 control-flow verification: {@code getHandHoldingItemAngle} is
 * reached only inside the {@code attachedToEntity.isFallFlying()} branch
 * after the {@code getLookAngle} call, so the shared actor snapshot is
 * always written before it is read.  The mixin is deliberately left
 * unchanged.</p>
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$boostDirection(LivingEntity attached, Operation<Vec3> original,
            @Share("gravityengineFireworkActor") LocalRef<VanillaActorSnapshot> ref) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(attached);
        ref.set(actor);
        return actor.transformedLook()
                ? VanillaPropulsionBridge.propulsionDirection(actor, 1.0D) : original.call(attached);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getHandHoldingItemAngle(Lnet/minecraft/world/item/Item;)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$handAttachment(LivingEntity attached, Item item, Operation<Vec3> original,
            @Share("gravityengineFireworkActor") LocalRef<VanillaActorSnapshot> ref) {
        VanillaActorSnapshot actor = ref.get();
        return actor.customBody() || actor.transformedLook()
                ? VanillaPropulsionBridge.fireworkHandOffset(attached, actor, item) : original.call(attached, item);
    }
}
