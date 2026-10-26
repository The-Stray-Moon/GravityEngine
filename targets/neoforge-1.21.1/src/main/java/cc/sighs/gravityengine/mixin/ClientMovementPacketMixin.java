package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientMovementBodyVersion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;

/** .249 ordinary movement sends pass through this native validation boundary. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientMovementPacketMixin {
    @WrapMethod(method = "send")
    private void gravityengine$bindMovement(Packet<?> packet, Operation<Void> original) {
        var player = Minecraft.getInstance().player;
        original.call(player == null ? packet : ClientMovementBodyVersion.envelope(player, packet, false));
    }
}
