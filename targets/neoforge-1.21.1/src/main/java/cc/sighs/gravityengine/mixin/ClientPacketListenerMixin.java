package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    /** Observe only the game-thread application, including direct history writes
     * and the outgoing Vanilla teleport acknowledgement. No packet is changed. */
    @WrapMethod(method = "handleMovePlayer", require = 1)
    private void gravityengine$traceViewCorrection(ClientboundPlayerPositionPacket packet, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED || !Minecraft.getInstance().isSameThread()) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-position-packet",
                "teleportId=%s packetP=%s packetYaw=%s packetPitch=%s relative=%s",
                packet.getId(), new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                packet.getYRot(), packet.getXRot(), packet.getRelativeArguments())) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleLookAt", require = 1)
    private void gravityengine$traceViewCommand(ClientboundPlayerLookAtPacket packet, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED || !Minecraft.getInstance().isSameThread()) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-look-at-packet")) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleSetEntityPassengersPacket", require = 1)
    private void gravityengine$traceViewPassenger(ClientboundSetPassengersPacket packet, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED || !Minecraft.getInstance().isSameThread()) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-passengers-packet")) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleRespawn", require = 1)
    private void gravityengine$traceViewRespawn(ClientboundRespawnPacket packet, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED || !Minecraft.getInstance().isSameThread()) { original.call(packet); return; }
        PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-respawn-before", "");
        try { original.call(packet); }
        finally { PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-respawn-after", ""); }
    }

    @WrapMethod(method = "handleLogin", require = 1)
    private void gravityengine$traceViewLogin(ClientboundLoginPacket packet, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED || !Minecraft.getInstance().isSameThread()) { original.call(packet); return; }
        PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-login-before", "");
        try { original.call(packet); }
        finally { PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-login-after", ""); }
    }
    @Unique private Vec3 gravityengine$correctionPositionAnchorBefore;
    @Unique private float gravityengine$correctionYawBefore;
    @Unique private float gravityengine$correctionPitchBefore;
    @Unique private boolean gravityengine$traceCorrection;
    @Unique private float gravityengine$correctionYawOldBefore;
    @Unique private float gravityengine$correctionPitchOldBefore;

    @Inject(method = "handlePlayerAbilities", at = @At("RETURN"))
    private void gravityengine$updateLocal(ClientboundPlayerAbilitiesPacket pkt, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateLocalAbilitySuppression(mc.player);
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 1)
    private void gravityengine$traceCorrectionIn(
            ClientboundPlayerPositionPacket packet,
            CallbackInfo ci
    ) {
        // PacketUtils queues the same handler on the game thread. Observe only
        // that application, not the earlier network-thread enqueue invocation.
        if (!Minecraft.getInstance().isSameThread()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        this.gravityengine$traceCorrection = player != null;
        if (!this.gravityengine$traceCorrection) return;
        this.gravityengine$correctionPositionAnchorBefore = player.position();
        this.gravityengine$correctionYawBefore = player.getYRot();
        this.gravityengine$correctionPitchBefore = player.getXRot();
        if (!GravityDebugLog.SPATIAL_STATE_ENABLED) return;
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
        if (player != null && GravityDebugLog.SPATIAL_STATE_ENABLED) {
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
