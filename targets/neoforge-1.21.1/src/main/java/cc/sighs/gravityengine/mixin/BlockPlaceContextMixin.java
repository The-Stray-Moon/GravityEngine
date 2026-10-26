package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPlacementBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Placement orientation selectors that can encode six world axes.
 *
 * <p>Vanilla derives these from scalar yaw/pitch on the raw player scalars.
 * Under custom semantic look the correct authority is the captured semantic
 * world direction, ranked into world {@code Direction}s using dot products
 * with Vanilla tie order.  The parent {@code UseOnContext} mixin handles
 * world-horizontal-only and scalar carriers.</p>
 */
@Mixin(BlockPlaceContext.class)
public abstract class BlockPlaceContextMixin {
    @Shadow protected boolean replaceClicked;

    @WrapMethod(method = "getNearestLookingDirection")
    private Direction gravityengine$nearestSixWayPlacement(
            Operation<Direction> original
    ) {
        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Player player = context.getPlayer();
        if (player == null) {
            return original.call();
        }
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.transformedLook()) {
            return original.call();
        }
        return VanillaPlacementBridge.nearestWorldDirection(actor);
    }

    @WrapMethod(method = "getNearestLookingVerticalDirection")
    private Direction gravityengine$nearestVerticalPlacement(
            Operation<Direction> original
    ) {
        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Player player = context.getPlayer();
        if (player == null) {
            return original.call();
        }
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.transformedLook()) {
            return original.call();
        }
        return VanillaPlacementBridge.nearestVerticalDirection(actor);
    }

    @WrapMethod(method = "getNearestLookingDirections")
    private Direction[] gravityengine$orderedLookingPlacement(
            Operation<Direction[]> original
    ) {
        BlockPlaceContext context = (BlockPlaceContext) (Object) this;
        Player player = context.getPlayer();
        if (player == null) {
            return original.call();
        }
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.transformedLook()) {
            return original.call();
        }
        Direction[] ordered =
                VanillaPlacementBridge.orderedLookingDirections(actor);
        if (!this.replaceClicked) {
            Direction opposite = context.getClickedFace().getOpposite();
            int index = 0;
            while (index < ordered.length && ordered[index] != opposite) {
                index++;
            }
            if (index > 0) {
                Direction[] adjusted = new Direction[ordered.length];
                for (int i = 0; i < ordered.length; i++) {
                    adjusted[i] = ordered[i];
                }
                System.arraycopy(ordered, 0, adjusted, 1, index);
                adjusted[0] = opposite;
                ordered = adjusted;
            }
        }
        return ordered;
    }
}
