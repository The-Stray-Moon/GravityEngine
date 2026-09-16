package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBlockResponse;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla retains bounce eligibility and coefficients.
 *
 * <p>The only translated representation is the native Y carrier:
 * for a custom-gravity landing it represents the actual contacted landing
 * normal rather than GravityFrame.up().</p>
 */
@Mixin({BedBlock.class, SlimeBlock.class})
public abstract class BounceBlockResponseMixin {
    @WrapOperation(
            method = "bounceUp",
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
            method = "bounceUp",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/Entity;"
                                    + "setDeltaMovement(DDD)V"
            )
    )
    private void gravityengine$world(
            Entity entity,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        Vec3 world =
                VanillaBlockResponse.fallWorld(
                        entity,
                        new Vec3(x, y, z)
                );

        original.call(
                entity,
                world.x,
                world.y,
                world.z
        );
    }
}