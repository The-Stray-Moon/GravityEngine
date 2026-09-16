package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.LlamaSpit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 21.1.249 LlamaSpit gravity bridge.
 *
 * <p>Vanilla {@code LlamaSpit.tick()} reproduces the ThrowableProjectile
 * ordering: hit-test on the current move vector, drag
 * {@code scale(0.99F)}, then a single post-drag {@code applyGravity()}, then
 * the position commit. Only that gravity call is wrapped; drag, motion
 * integration/order, hit policy and entity lifecycle remain Vanilla-owned.
 * WindCharge, FireworkRocket, ShulkerBullet, FishingHook and guided/
 * tethered projectiles are not affected.</p>
 */
@Mixin(LlamaSpit.class)
public abstract class LlamaSpitGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/"
                            + "LlamaSpit;applyGravity()V"
            ),
            require = 1
    )
    private void gravityengine$replaceVanillaGravity(
            LlamaSpit spit,
            Operation<Void> original
    ) {
        if (!BallisticGravityIntegration.applyGravity(spit, 1.0D)) {
            original.call(spit);
        }
    }
}
