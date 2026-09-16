package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPlacementBridge;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placement orientation carriers.
 *
 * <p>{@code UseOnContext} exposes two restricted Vanilla representations:
 * world-horizontal {@code Direction} (used by facing properties) and scalar
 * world-Y rotation (used by entities/blocks that consume raw yaw).  Neither
 * can encode a custom gravity tangent plane.  This bridge projects semantic
 * heading into the world-XZ/Y representation with a deterministic
 * near-vertical fallback and never feeds back into Qbody or semantic look.
 */
@Mixin(UseOnContext.class)
public abstract class UseOnContextMixin {
    @WrapMethod(method = "getHorizontalDirection")
    private Direction gravityengine$worldHorizontalPlacement(
            Operation<Direction> original
    ) {
        Player player = ((UseOnContext) (Object) this).getPlayer();
        if (player == null) {
            return original.call();
        }
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.transformedLook()) {
            return original.call();
        }
        return VanillaPlacementBridge.horizontalDirection(
                actor, player.getYRot());
    }

    @WrapMethod(method = "getRotation")
    private float gravityengine$worldYawPlacement(
            Operation<Float> original
    ) {
        Player player = ((UseOnContext) (Object) this).getPlayer();
        if (player == null) {
            return original.call();
        }
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.transformedLook()) {
            return original.call();
        }
        return VanillaPlacementBridge.worldYawDegrees(
                actor, player.getYRot());
    }
}
