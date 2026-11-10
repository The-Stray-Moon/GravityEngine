package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketSpatialDebugMixin {
    @Unique private Vec3 gravityengine$correctionPositionAnchorBefore;
    @Unique private float gravityengine$correctionYawBefore;
    @Unique private float gravityengine$correctionPitchBefore;
    @Unique private boolean gravityengine$traceCorrection;
    @Unique private float gravityengine$correctionYawOldBefore;
    @Unique private float gravityengine$correctionPitchOldBefore;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 1)
    private void gravityengine$traceCorrectionIn(
            ClientboundPlayerPositionPacket packet,
            CallbackInfo ci
    ) {
        // PacketUtils queues the same handler on the game thread. Observe only
        // that application, not the earlier network-thread enqueue invocation.
        if (!Minecraft.getInstance().isSameThread()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        this.gravityengine$traceCorrection = player != null && GravityDebugLog.shouldLogSpatialState(player);
        if (!this.gravityengine$traceCorrection) return;
        this.gravityengine$correctionPositionAnchorBefore = player.position();
        this.gravityengine$correctionYawBefore = player.getYRot();
        this.gravityengine$correctionPitchBefore = player.getXRot();
        if (!GravityDebugLog.shouldLogSpatialState(player)) return;
        // Vanilla interpolation carriers, observed only; never rewritten here.
        this.gravityengine$correctionYawOldBefore = player.yRotO;
        this.gravityengine$correctionPitchOldBefore = player.xRotO;
        GravityDebugLog.spatialState(player, "MOVE", "client-correction-in",
                "packetTeleportId=%s packetPosition=%s packetYaw=%s packetPitch=%s relativeArguments=%s "
                        + "positionAnchorBefore=%s yawBefore=%s pitchBefore=%s yawOldBefore=%s pitchOldBefore=%s",
                packet.getId(), GravityDebugLog.exactVec(new Vec3(packet.getX(), packet.getY(), packet.getZ())),
                packet.getYRot(), packet.getXRot(), packet.getRelativeArguments(),
                GravityDebugLog.exactVec(this.gravityengine$correctionPositionAnchorBefore),
                this.gravityengine$correctionYawBefore, this.gravityengine$correctionPitchBefore,
                this.gravityengine$correctionYawOldBefore, this.gravityengine$correctionPitchOldBefore);
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"), require = 1)
    private void gravityengine$traceCorrectionOut(
            ClientboundPlayerPositionPacket packet,
            CallbackInfo ci
    ) {
        if (!this.gravityengine$traceCorrection) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && GravityDebugLog.shouldLogSpatialState(player)) {
            GravityDebugLog.spatialState(player, "MOVE", "client-correction-out",
                    "packetTeleportId=%s positionAnchorBefore=%s positionAnchorAfter=%s yawBefore=%s yawAfter=%s "
                            + "pitchBefore=%s pitchAfter=%s yawOldBefore=%s yawOldAfter=%s "
                            + "pitchOldBefore=%s pitchOldAfter=%s",
                    packet.getId(), GravityDebugLog.exactVec(this.gravityengine$correctionPositionAnchorBefore),
                    GravityDebugLog.exactVec(player.position()),
                    this.gravityengine$correctionYawBefore, player.getYRot(),
                    this.gravityengine$correctionPitchBefore, player.getXRot(),
                    this.gravityengine$correctionYawOldBefore, player.yRotO,
                    this.gravityengine$correctionPitchOldBefore, player.xRotO);
        }
        this.gravityengine$traceCorrection = false;
        this.gravityengine$correctionPositionAnchorBefore = null;
    }
}
