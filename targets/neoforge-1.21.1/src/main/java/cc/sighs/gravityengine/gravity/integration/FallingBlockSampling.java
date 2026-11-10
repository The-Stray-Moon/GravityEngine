package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Canonical logical center of a FallingBlock.
 *
 * <p>This deliberately does not use the entity's 0.98-wide AABB center.
 * Block-state sampling and entity-state sampling must address the same
 * logical material point.
 */
public final class FallingBlockSampling {
    private FallingBlockSampling() {}

    public static Vec3d blockCenter(BlockPos source) {
        return new Vec3d(
                source.getX() + 0.5D,
                source.getY() + 0.5D,
                source.getZ() + 0.5D
        );
    }

    public static Vec3 entityCenter(FallingBlockEntity entity) {
        return new Vec3(
                entity.getX(),
                entity.getY() + 0.5D,
                entity.getZ()
        );
    }
}