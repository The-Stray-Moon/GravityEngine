package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionCoordinator;
import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;

/** Native explicit rotation is a discrete semantic handoff, never continuous
 * scalar feedback. Vanilla retains position, rotation math and teleport ACKs. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketLookAuthorityMixin {
    @WrapMethod(method = "handleMovePlayer", require = 1)
    private void gravityengine$explicitLook(ClientboundPlayerPositionPacket packet, Operation<Void> original) {
        var mc = Minecraft.getInstance();
        boolean positionOnly = packet.getRelativeArguments().containsAll(RelativeMovement.ROTATION)
                && packet.getYRot() == 0 && packet.getXRot() == 0;
        if (!mc.isSameThread() || mc.player == null || positionOnly) {
            original.call(packet);
            return;
        }
        BodyAttitudeTransactionCoordinator.projectActiveLook(mc.player);
        original.call(packet);
        BodyAttitudeTransactionCoordinator.acceptExplicitLook(mc.player);
        ClientBodyAttitudeControl.onExplicitLook(mc.player);
    }
}
