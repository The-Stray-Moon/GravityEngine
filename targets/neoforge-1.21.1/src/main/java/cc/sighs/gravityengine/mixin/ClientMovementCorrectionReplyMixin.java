package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientMovementBodyVersion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native correction ACK is followed by a direct Connection.send movement.
 * It uses the just-installed body, not a retiring prediction scope. */
@Mixin(ClientPacketListener.class)
public abstract class ClientMovementCorrectionReplyMixin {
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 2, allow = 2)
    private void gravityengine$bindCorrectionReply(Connection connection, Packet<?> packet, Operation<Void> original) {
        original.call(connection, ClientMovementBodyVersion.envelope(Minecraft.getInstance().player, packet, true));
    }
}
