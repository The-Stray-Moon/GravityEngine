package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.GravityPresentationIntegration;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Converts PlayerRenderer's vanilla world-Y crouch render offset into the
 * gravity-relative presentation down axis.
 *
 * <p>EntityRenderDispatcher applies this offset before invoking the living
 * renderer.  Therefore this method must return an already world-space offset;
 * it must NOT return a local vector and expect the later model rotation to
 * rotate it.</p>
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(
            method = "getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void gravityengine$gravityCrouchRenderOffset(
            AbstractClientPlayer player,
            float partialTick,
            CallbackInfoReturnable<Vec3> cir
    ) {
        if (!player.isCrouching()) {
            return;
        }

        if (!GravityInfluencePolicy
                .usesCustomPresentation(player)) {
            return;
        }

        var snapshot =
                GravityPresentationIntegration.snapshot(
                        player,
                        partialTick
                );

        /*
         * Vanilla magnitude:
         *
         *     entity.getScale() * -2 / 16 on world Y
         *
         * Positive scalar projected along gravity-down produces exactly the
         * same vector for vanilla gravity:
         *
         *     down=(0,-1,0) -> (0,-magnitude,0)
         */
        double magnitude =
                (double) player.getScale()
                        * 2.0D
                        / 16.0D;

        cir.setReturnValue(
                snapshot.frame()
                        .down()
                        .scale(magnitude)
        );
    }
}
