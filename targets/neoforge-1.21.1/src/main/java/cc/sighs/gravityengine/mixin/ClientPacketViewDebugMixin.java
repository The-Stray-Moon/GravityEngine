package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketViewDebugMixin {
    /** Observe only the game-thread application, including direct history writes
     * and the outgoing Vanilla teleport acknowledgement. No packet is changed. */
    @WrapMethod(method = "handleMovePlayer", require = 1)
    private void gravityengine$traceViewCorrection(ClientboundPlayerPositionPacket packet, Operation<Void> original) {
        if (!Minecraft.getInstance().isSameThread() || !PlayerViewDebugLog.shouldLog(Minecraft.getInstance().player)) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-position-packet",
                "teleportId=%s packetP=%s packetYaw=%s packetPitch=%s relative=%s",
                packet.getId(), new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                packet.getYRot(), packet.getXRot(), packet.getRelativeArguments())) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleLookAt", require = 1)
    private void gravityengine$traceViewCommand(ClientboundPlayerLookAtPacket packet, Operation<Void> original) {
        if (!Minecraft.getInstance().isSameThread() || !PlayerViewDebugLog.shouldLog(Minecraft.getInstance().player)) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-look-at-packet")) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleSetEntityPassengersPacket", require = 1)
    private void gravityengine$traceViewPassenger(ClientboundSetPassengersPacket packet, Operation<Void> original) {
        if (!Minecraft.getInstance().isSameThread() || !PlayerViewDebugLog.shouldLog(Minecraft.getInstance().player)) { original.call(packet); return; }
        try (var trace = PlayerViewDebugLog.begin(Minecraft.getInstance().player, "client-passengers-packet")) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleRespawn", require = 1)
    private void gravityengine$traceViewRespawn(ClientboundRespawnPacket packet, Operation<Void> original) {
        if (!Minecraft.getInstance().isSameThread() || !PlayerViewDebugLog.shouldLog(Minecraft.getInstance().player)) { original.call(packet); return; }
        PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-respawn-before", "");
        try { original.call(packet); }
        finally { PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-respawn-after", ""); }
    }

    @WrapMethod(method = "handleLogin", require = 1)
    private void gravityengine$traceViewLogin(ClientboundLoginPacket packet, Operation<Void> original) {
        if (!Minecraft.getInstance().isSameThread() || !PlayerViewDebugLog.shouldLog(Minecraft.getInstance().player)) { original.call(packet); return; }
        PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-login-before", "");
        try { original.call(packet); }
        finally { PlayerViewDebugLog.event(Minecraft.getInstance().player, "client-login-after", ""); }
    }
}
