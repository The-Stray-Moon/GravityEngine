package cc.sighs.gravityengine.gravity.integration.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Pinned 2.0.5 storage-domain exclusion, including currently unallocated plots. */
final class SablePlotCoordinates {
    private SablePlotCoordinates() {}
    static boolean contains(Level level, BlockPos pos) {
        var container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(pos);
    }
}
