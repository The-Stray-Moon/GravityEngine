package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.collision.BlockMovementMaterialSnapshot;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.debug.GravityInvariant;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 21.1.249 getBlockSpeedFactor: Vanilla owns current-cell/water/bubble/support
 * precedence. Only its material operands come from the frozen movement scene.
 * Carrier states encode the two native type checks; they are never installed.
 */
@Mixin(Entity.class)
public abstract class EntityBlockSpeedMixin {
    @Unique
    private boolean gravityengine$capturedSpeed() {
        var entity = (Entity) (Object) this;
        return GravityInfluencePolicy.usesCustomCollision(entity)
                && GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime().isInMove();
    }

    @WrapMethod(method = "getBlockSpeedFactor")
    private float gravityengine$coverage(Operation<Float> original) {
        try {
            return original.call();
        } catch (CollisionSceneCoverageException uncovered) {
            GravityInvariant.report((Entity) (Object) this, "block-speed-material-coverage", true,
                    () -> uncovered.getMessage());
            // No damping without material evidence. Never query the live world.
            return 1.0F;
        }
    }

    @WrapOperation(method = "getBlockSpeedFactor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 0), require = 1)
    private BlockState gravityengine$current(Level level, BlockPos pos, Operation<BlockState> original,
            @Share("material") LocalRef<BlockMovementMaterialSnapshot> material) {
        if (!gravityengine$capturedSpeed()) return original.call(level, pos);
        return gravityengine$material(pos, material);
    }

    @WrapOperation(method = "getBlockSpeedFactor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 1), require = 1)
    private BlockState gravityengine$support(Level level, BlockPos pos, Operation<BlockState> original,
            @Share("material") LocalRef<BlockMovementMaterialSnapshot> material) {
        if (!gravityengine$capturedSpeed()) return original.call(level, pos);
        var support = GravityEntityAccess.cast((Entity) (Object) this).gravityengine$getVanillaSupportingBlock();
        if (support.isPresent()) return gravityengine$material(support.get(), material);
        material.set(BlockMovementMaterialSnapshot.AIR);
        return Blocks.AIR.defaultBlockState();
    }

    @Unique
    private BlockState gravityengine$material(BlockPos pos, LocalRef<BlockMovementMaterialSnapshot> captured) {
        var runtime = GravityEntityAccess.cast((Entity) (Object) this).gravityengine$gravityComponent().runtime();
        var operation = runtime.collisionOperation();
        if (operation == null) throw new IllegalStateException("movement material requires an operation scene");
        var material = operation.scene().movementMaterialAt(pos).orElse(BlockMovementMaterialSnapshot.AIR);
        captured.set(material);
        return (material.water() ? Blocks.WATER : material.bubbleColumn() ? Blocks.BUBBLE_COLUMN : Blocks.AIR)
                .defaultBlockState();
    }

    @WrapOperation(method = "getBlockSpeedFactor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getSpeedFactor()F"), require = 2)
    private float gravityengine$factor(Block block, Operation<Float> original,
            @Share("material") LocalRef<BlockMovementMaterialSnapshot> material) {
        return material.get() == null ? original.call(block) : material.get().speedFactor();
    }
}
