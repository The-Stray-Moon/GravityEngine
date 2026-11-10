package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ItemCorrectionPresentation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class ClientItemCorrectionMixin {
    // 1.21.1: both native position handlers invoke Entity.lerpTo on the
    // client thread. Absolute packets also represent ordinary long falls.
    @WrapOperation(method = {"handleMoveEntity", "handleTeleportEntity"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"))
    private void gravityengine$itemCorrection(Entity entity, double x, double y, double z,
            float yaw, float pitch, int steps, Operation<Void> original) {
        var before = entity.position();
        original.call(entity, x, y, z, yaw, pitch, steps);
        ItemCorrectionPresentation.corrected(entity, before);
    }
}
