package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.collision.BlockObstacle;
import cc.sighs.gravityengine.gravity.collision.CapturedCollisionScene;
import cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException;
import cc.sighs.gravityengine.gravity.collision.CollisionObstacle;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Shared capture-side helpers only.
 *
 * <p>There is intentionally no solver-facing world-query facade here. Live
 * Minecraft reads happen exactly once in {@link MinecraftCollisionSceneCapture};
 * {@link CapturedCollisionScene} only filters immutable obstacle data.</p>
 */
final class CollisionWorldQuery {
    static final long MAX_BLOCK_POSITIONS_PER_QUERY = 262_144L;

    private CollisionWorldQuery() {}

    /**
     * Enumerates the exact same pieces as {@link VoxelShape#toAabbs()}, with a
     * hard early abort. Vanilla toAabbs collects this forAllBoxes traversal; keeping
     * it streaming preserves the existing primitive-budget guard before allocation.
     *
     * <p>{@link VoxelShape#forAllBoxes} is not cancellable, so when the
     * primitive budget is exhausted the visitor throws a private sentinel that
     * immediately unwinds the enumeration. The sentinel is caught here and can
     * never escape the capture layer; the tracker already carries the
     * complexity-limit reason and the capture boundary fail-closes.</p>
     */
    static void enumerateShapePrimitives(
            VoxelShape shape,
            CollisionWorkTracker tracker,
            boolean enforcePrimitiveBudget,
            BlockPos position,
            List<BlockObstacle> built
    ) {
        if (shape.isEmpty()) {
            return;
        }

        try {
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                if (enforcePrimitiveBudget && !tracker.recordObstacles(1)) {
                    throw PrimitiveEnumerationAbort.INSTANCE;
                }

                built.add(new BlockObstacle(
                        MinecraftMathAdapter.toCellPos(position),
                        MinecraftCollisionGeometryAdapter.toAabb3d(
                                new AABB(
                                        minX, minY, minZ,
                                        maxX, maxY, maxZ
                                ).move(position)
                        )
                ));
            });
        } catch (PrimitiveEnumerationAbort aborted) {
            // The tracker already records budget exhaustion.
            // Other exceptions must propagate.
        }
    }

    /** Sorts obstacles deterministically by type and provenance key. */
    static void sortObstacles(List<CollisionObstacle> obstacles) {
        obstacles.sort(CollisionObstacle.STABLE_COMPARATOR);
    }

    static void requireBoundedBlockVolume(
            BlockPos minimum,
            BlockPos maximum
    ) {
        long xCount = (long) maximum.getX() - minimum.getX() + 1L;
        long yCount = (long) maximum.getY() - minimum.getY() + 1L;
        long zCount = (long) maximum.getZ() - minimum.getZ() + 1L;
        if (xCount <= 0L || yCount <= 0L || zCount <= 0L
                || xCount > MAX_BLOCK_POSITIONS_PER_QUERY
                || yCount > MAX_BLOCK_POSITIONS_PER_QUERY
                || zCount > MAX_BLOCK_POSITIONS_PER_QUERY
                || xCount > MAX_BLOCK_POSITIONS_PER_QUERY / yCount
                || xCount * yCount > MAX_BLOCK_POSITIONS_PER_QUERY / zCount) {
            throw new CollisionComplexityLimitException(
                    "collision block query exceeds complexity guard: min="
                            + minimum
                            + " max=" + maximum
            );
        }
    }

    /**
     * Private internal control flow only: aborts a non-cancellable
     * {@code VoxelShape} enumeration when the primitive budget is exhausted.
     * It is caught immediately inside {@link #enumerateShapePrimitives} and
     * never escapes the collision capture layer.
     */
    private static final class PrimitiveEnumerationAbort
            extends RuntimeException {
        static final PrimitiveEnumerationAbort INSTANCE =
                new PrimitiveEnumerationAbort();

        private PrimitiveEnumerationAbort() {
            super(null, null, false, false);
        }
    }
}
