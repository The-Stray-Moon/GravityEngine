package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
public abstract class FallingBlockScheduledTickMixin {
    @WrapOperation(method="tickBlock", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"), require=1)
    private void gravityengine$receipt(BlockState state, ServerLevel level, BlockPos pos,
                                      RandomSource random, Operation<Void> original) {
        if (state.getBlock() instanceof FallingBlock) {
            var runtime = GravityFieldRuntime.getIfPresent(level);
            if (runtime != null) runtime.fallingBlocks().nativeExecution(level,pos);
        }
        original.call(state,level,pos,random);
    }
}
