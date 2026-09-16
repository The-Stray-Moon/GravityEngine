package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Brush dust/view direction.
 *
 * <p>{@code BrushItem} consumes the corrected {@code ProjectileUtil}
 * view-vector path for its target; its dust particles additionally consume
 * {@code LivingEntity.getViewVector(0)} directly.  That narrow dust consumer
 * is patched here without touching the shared accessor.</p>
 */
@Mixin(BrushItem.class)
public abstract class BrushItemMixin {
    @WrapOperation(
            method = "onUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$brushDustViewDirection(
            LivingEntity entity,
            float partialTick,
            Operation<Vec3> original
    ) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(entity);
        return !actor.transformedLook()
                ? original.call(entity, partialTick)
                : actor.viewForward();
    }
}
