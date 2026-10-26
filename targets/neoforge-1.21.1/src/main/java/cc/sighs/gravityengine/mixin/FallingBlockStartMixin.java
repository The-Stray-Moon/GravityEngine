package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.FallingBlockStartIntegration;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallingBlock.class)
public abstract class FallingBlockStartMixin {
    /** 21.1.249: isFree(getBlockState(pos.below())) precedes minY, fall(), falling(). */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1, allow = 1)
    private BlockState gravityengine$startSupport(ServerLevel level, BlockPos below,
            Operation<BlockState> original, @Local(argsOnly = true) BlockPos source) {
        return SableMovementCompatibility.isPlotPosition(level, source)
                ? original.call(level, below) : FallingBlockStartIntegration.supportState(level, source);
    }
}
