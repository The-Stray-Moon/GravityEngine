package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaTargetingBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Server/gameplay block targeting for item use.
 *
 * <p>21.1.249 semantic seam: {@code Item.getPlayerPOVHitResult} composes one
 * ray from {@code player.getEyePosition()} and
 * {@code player.calculateViewVector(...)}.  The exact version-matched source
 * has no narrower seam that can keep those two operands coherent, so this
 * mixin replaces the static method only for actors whose semantic look is not
 * VANILLA.  Vanilla retains interaction range, fluid mode, clipping and
 * callback ordering; the bridge owns only physical eye and semantic direction.
 */
@Mixin(Item.class)
public abstract class ItemMixin {
    @WrapMethod(method = "getPlayerPOVHitResult")
    private static BlockHitResult gravityengine$coherentItemLook(
            Level level,
            Player player,
            ClipContext.Fluid fluidMode,
            Operation<BlockHitResult> original
    ) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(player);
        if (!actor.customBody()
                && !actor.transformedLook()) {
            return original.call(level, player, fluidMode);
        }
        VanillaTargetingBridge.ViewRay ray =
                VanillaTargetingBridge.viewRay(
                        actor, player.blockInteractionRange());
        return level.clip(new ClipContext(
                ray.origin(),
                ray.end(),
                ClipContext.Block.OUTLINE,
                fluidMode,
                player
        ));
    }
}
