package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaTargetingBridge;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Server interaction-reach bridge.
 *
 * <p>The 1.21.1 entity overload is the last seam that retains target identity
 * before Vanilla reduces the target to an AABB. Ordinary target geometry and
 * all range policy remain Vanilla-equivalent; a custom target substitutes
 * exact OBB closest-point distance. Player eye origin follows physical body
 * geometry where required.</p>
 */
@Mixin(Player.class)
public abstract class PlayerInteractionMixin {
    @WrapMethod(
            method =
                    "canInteractWithEntity"
                            + "(Lnet/minecraft/world/entity/Entity;D)Z"
    )
    private boolean gravityengine$entityInteractionReach(
            Entity target,
            double distance,
            Operation<Boolean> original
    ) {
        Player player =
                (Player) (Object) this;

        if (target.isRemoved()) {
            return false;
        }

        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(player);

        boolean targetCustom =
                GravityInfluencePolicy
                        .usesCustomBody(target);

        if (!actor.customBody()
                && !targetCustom) {
            return original.call(
                    target,
                    distance
            );
        }

        double range =
                player.entityInteractionRange()
                        + distance;

        return VanillaTargetingBridge.squaredDistanceToTargetBody(
                        target,
                        actor.eye()
                ) < range * range;
    }

    @WrapMethod(
            method =
                    "canInteractWithBlock"
                            + "(Lnet/minecraft/core/BlockPos;D)Z"
    )
    private boolean gravityengine$blockInteractionReach(
            BlockPos pos,
            double distance,
            Operation<Boolean> original
    ) {
        Player player =
                (Player) (Object) this;

        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(player);

        if (!actor.customBody()) {
            return original.call(
                    pos,
                    distance
            );
        }

        double range =
                player.blockInteractionRange()
                        + distance;

        return new AABB(pos)
                .distanceToSqr(actor.eye())
                < range * range;
    }
}
