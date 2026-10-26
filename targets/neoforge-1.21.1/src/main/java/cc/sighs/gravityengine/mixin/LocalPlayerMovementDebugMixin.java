package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMovementDebugMixin {
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void gravityengine$logOutgoingMove(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!GravityDebugLog.shouldLog(player)) return;
        GravityDebugLog.log(
                player,
                "client-move-out",
                "position=%s %s yaw=%.3f pitch=%.3f onGround=%s",
                GravityDebugLog.vec(player.position()),
                GravityDebugLog.formatEntityVelocity(player),
                player.getYRot(),
                player.getXRot(),
                player.onGround()
        );
    }

    @WrapOperation(
            method = {"sendPosition", "tick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void gravityengine$traceActualMovePacket(
            ClientPacketListener connection,
            Packet<?> packet,
            Operation<Void> original
    ) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 1.21.1 sendPosition chooses Pos/PosRot/Rot/StatusOnly; tick also
        // sends passenger rotation. Observe immediately before the real send.
        if (GravityDebugLog.shouldLog(player)
                && packet instanceof ServerboundMovePlayerPacket move
                && GravityInfluencePolicy.usesCustomCollision(player)) {
            var packetPosition = new net.minecraft.world.phys.Vec3(
                    move.getX(player.getX()),
                    move.getY(player.getY()),
                    move.getZ(player.getZ())
            );
            GravityDebugLog.log(
                    player,
                    "client-packet-send",
                    "packetType=%s carriesPosition=%s carriesRotation=%s packetPosition=%s "
                            + "currentPosition=%s packetYaw=%.3f packetPitch=%.3f "
                            + "currentLocalYaw=%.3f currentLocalPitch=%.3f "
                            + "packetOnGround=%s currentOnGround=%s %s",
                    packet.getClass().getSimpleName(),
                    move.hasPosition(),
                    move.hasRotation(),
                    GravityDebugLog.vec(packetPosition),
                    GravityDebugLog.vec(player.position()),
                    move.getYRot(player.getYRot()),
                    move.getXRot(player.getXRot()),
                    player.getYRot(),
                    player.getXRot(),
                    move.isOnGround(),
                    player.onGround(),
                    GravityDebugLog.formatEntityVelocity(player)
            );
        }
        original.call(connection, packet);
    }

}
