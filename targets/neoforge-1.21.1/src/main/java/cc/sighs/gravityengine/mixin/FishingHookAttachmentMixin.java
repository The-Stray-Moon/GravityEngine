package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FishingHook entity-attachment spatial bridge (21.1.249).
 *
 * <p>In the {@code HOOKED_IN_ENTITY} branch of {@code FishingHook.tick()}
 * Vanilla follows the attached target with the world-Y body point
 * {@code hookedIn.getY(0.8)}.  This is target body geometry, not
 * environmental vertical: for a custom exact body the equivalent point must
 * follow the target's body orientation.  The branch's single
 * {@code setPos(x, y, z)} invocation is translated through the reusable
 * body-height-point bridge.  Fluid-surface/block geometry, retrieval and
 * lifetime policy remain Vanilla-owned.</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookAttachmentMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/"
                            + "FishingHook;setPos(DDD)V"
            ),
            require = 1
    )
    private void gravityengine$attachedTargetBodyPoint(
            FishingHook hook,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        // The wrapped setPos invocation is the HOOKED_IN_ENTITY follow; the
        // hooked entity is a method local at that point, so it is resolved
        // through the current entity data rather than recaptured here.
        Entity attached = hook.getHookedIn();
        if (attached == null) {
            original.call(hook, x, y, z);
            return;
        }
        Vec3 point = VanillaProjectileBridge.bodyHeightPoint(
                attached, 0.8D);
        original.call(hook, point.x, point.y, point.z);
    }
}
