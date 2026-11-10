package cc.sighs.gravityengine.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 21.1.249 visible holders, consumed incrementally only while publications exist. */
@Mixin(ChunkMap.class)
public interface ChunkMapFallingAccess {
    @Invoker("getChunks") Iterable<ChunkHolder> gravityengine$visibleChunks();
}
