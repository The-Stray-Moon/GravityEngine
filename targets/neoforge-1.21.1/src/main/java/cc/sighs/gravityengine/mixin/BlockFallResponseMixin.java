package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBlockResponse;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1.249 Block.updateEntityAfterFallOn keeps Vanilla's native
 * zero-Y formula and call ordering. VanillaBlockResponse maps that temporary
 * Y carrier onto the actual gravity-down landing contact normal when custom
 * collision published one.
 *
 * <p>The Entity velocity outside these two native expressions always remains
 * world-space.</p>
 */
@Mixin(Block.class)
public abstract class BlockFallResponseMixin {
    @WrapOperation(
            method = "updateEntityAfterFallOn",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/Entity;"
                                    + "getDeltaMovement()"
                                    + "Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 gravityengine$local(
            Entity entity,
            Operation<Vec3> original
    ) {
        return VanillaBlockResponse.fallLocal(
                entity,
                original.call(entity)
        );
    }

    @WrapOperation(
            method = "updateEntityAfterFallOn",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/Entity;"
                                    + "setDeltaMovement("
                                    + "Lnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void gravityengine$world(
            Entity entity,
            Vec3 local,
            Operation<Void> original
    ) {
        var expected = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState().currentMoveResult();
        original.call(
                entity,
                VanillaBlockResponse.fallWorld(entity, local)
        );
        VanillaBlockResponse.recordFallWrite(entity, expected);
    }
}
