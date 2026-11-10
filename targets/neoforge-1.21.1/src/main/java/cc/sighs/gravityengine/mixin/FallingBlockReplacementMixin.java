package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class FallingBlockReplacementMixin {
    @Inject(method="setBlockState",at=@At("RETURN"),require=1)
    private void gravityengine$invalidate(BlockPos pos, BlockState state, boolean moving,
                                         CallbackInfoReturnable<BlockState> result) {
        var old = result.getReturnValue();
        if (old == null || !(old.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return;
        if (((LevelChunk)(Object)this).getLevel() instanceof ServerLevel level) {
            var runtime = GravityFieldRuntime.getIfPresent(level);
            if (runtime != null) runtime.fallingBlocks().changed(level,pos);
        }
    }
}
