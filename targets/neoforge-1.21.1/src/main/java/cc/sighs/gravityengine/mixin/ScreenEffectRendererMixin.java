package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientGravityFrameSampler;
import cc.sighs.gravityengine.client.GravityPresentationIntegration;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces ScreenEffectRenderer's world-Y eye probes for custom-gravity
 * first-person presentation.
 *
 * Vanilla 1.21.1 getOverlayBlock(Player) samples eight points around:
 *
 *     (player.getX(), player.getEyeY(), player.getZ())
 *
 * using world X/Y/Z offsets.
 *
 * Under GravityEngine the camera/eye lives in a gravity-relative frame, so those
 * probes can incorrectly enter the support block when the player is standing
 * on a wall or ceiling.
 *
 * This hook preserves vanilla's eight-probe topology and dimensions, replacing
 * only the world-axis assumption with presentation right/up/forward axes.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

    @Inject(
            method = "getOverlayBlock(Lnet/minecraft/world/entity/player/Player;)Lorg/apache/commons/lang3/tuple/Pair;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private static void gravityengine$gravityOverlayBlock(
            Player player,
            CallbackInfoReturnable<Pair<BlockState, BlockPos>> cir
    ) {
        /*
         * This is a camera/screen presentation consumer, so use the same
         * eligibility rule as CameraMixin rather than custom-body ownership.
         *
         * Sleeping currently deliberately keeps the vanilla camera path.
         */
        if (!GravityPresentationIntegration.usesCameraPresentation(player)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();

        float partialTick = camera.getPartialTickTime();

        ClientGravityFrameSampler.RenderSnapshot snapshot =
                GravityPresentationIntegration.snapshot(
                        player,
                        partialTick
                );

        /*
         * For the actually rendered local player, the screen effect should be
         * tested around the actual camera position.
         *
         * This is intentionally preferable here to player.getEyeY() and also
         * preserves Camera's own crouch/pose eye-height smoothing.
         *
         * The fallback keeps the helper valid if getOverlayBlock is invoked
         * while the main camera is temporarily attached to another entity.
         */
        Vec3 eyeCenter;
        if (camera.getEntity() == player) {
            eyeCenter = camera.getPosition();
        } else {
            eyeCenter = GravityPresentationIntegration.pickingEyePosition(
                    player,
                    partialTick
            );
        }

        Vec3 right = snapshot.frame().left().reverse();
        Vec3 up = snapshot.frame().up();
        Vec3 forward = snapshot.frame().forward();

        /*
         * Vanilla:
         *
         * X/Z:
         *   (((bit) - 0.5F) * bbWidth * 0.8F)
         *   => +/- bbWidth * 0.4
         *
         * Y:
         *   (((bit) - 0.5F) * 0.1F * scale)
         *   => +/- 0.05 * scale
         *
         * Keep those exact dimensions, merely rotate their basis.
         */
        double horizontalHalfExtent =
                (double) player.getBbWidth() * 0.4D;

        double verticalHalfExtent =
                (double) player.getScale() * 0.05D;

        BlockPos.MutableBlockPos mutablePos =
                new BlockPos.MutableBlockPos();

        for (int i = 0; i < 8; i++) {
            double sideSign =
                    ((i & 1) == 0) ? -1.0D : 1.0D;

            double upSign =
                    ((i & 2) == 0) ? -1.0D : 1.0D;

            double forwardSign =
                    ((i & 4) == 0) ? -1.0D : 1.0D;

            Vec3 sample = eyeCenter
                    .add(right.scale(
                            sideSign * horizontalHalfExtent
                    ))
                    .add(up.scale(
                            upSign * verticalHalfExtent
                    ))
                    .add(forward.scale(
                            forwardSign * horizontalHalfExtent
                    ));

            mutablePos.set(
                    sample.x,
                    sample.y,
                    sample.z
            );

            BlockState state =
                    player.level().getBlockState(mutablePos);

            if (state.getRenderShape() != RenderShape.INVISIBLE
                    && state.isViewBlocking(
                    player.level(),
                    mutablePos
            )) {
                cir.setReturnValue(
                        Pair.of(
                                state,
                                mutablePos.immutable()
                        )
                );
                return;
            }
        }

        /*
         * We own the custom-gravity query completely.
         *
         * No custom sample was inside a view-blocking block, therefore the
         * result is null. Do NOT fall through to vanilla, because vanilla would
         * immediately repeat the incorrect world-Y probe and recreate the bug.
         */
        cir.setReturnValue(null);
    }
}