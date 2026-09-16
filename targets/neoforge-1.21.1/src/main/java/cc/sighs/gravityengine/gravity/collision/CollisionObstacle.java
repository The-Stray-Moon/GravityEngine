package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Comparator;

/**
 * Provenance-preserving sealed collision obstacle model.
 *
 * <p>The kernel-facing geometry surface is neutral ({@link Aabb3d} plus JOML
 * vectors).  Minecraft-specific values such as {@link BlockPos} and entity
 * ids are retained only as opaque provenance for deterministic ordering and
 * gameplay bookkeeping; no geometric calculation consumes them here.</p>
 */
public sealed interface CollisionObstacle
        permits BlockObstacle,
                EntityObstacle,
                WorldBorderObstacle,
                SphereObstacle {

    Comparator<CollisionObstacle> STABLE_COMPARATOR = CollisionObstacle::compareStable;

    Aabb3d broadphaseBounds();

    /** Material-point velocity in blocks/tick at an absolute normalized operation time (defensive copy). */
    Vector3d velocityAt(Vector3dc worldPoint, double time);

    private static int compareStable(CollisionObstacle left, CollisionObstacle right) {
        int cmp = Integer.compare(typeOrder(left), typeOrder(right));
        if (cmp != 0) return cmp;
        return switch (left) {
            case BlockObstacle a -> compareBlock(a, (BlockObstacle) right);
            case EntityObstacle a -> compareEntity(a, (EntityObstacle) right);
            case WorldBorderObstacle a -> compareAabb(a.bounds(), ((WorldBorderObstacle) right).bounds());
            case SphereObstacle a -> compareSphere(a, (SphereObstacle) right);
        };
    }

    private static int typeOrder(CollisionObstacle obstacle) {
        return switch (obstacle) {
            case BlockObstacle ignored -> 0;
            case EntityObstacle ignored -> 1;
            case WorldBorderObstacle ignored -> 2;
            case SphereObstacle ignored -> 3;
        };
    }

    private static int compareBlock(BlockObstacle left, BlockObstacle right) {
        int cmp = compareBlockPos(left.blockPos(), right.blockPos());
        if (cmp != 0) return cmp;
        return compareAabb(left.bounds(), right.bounds());
    }

    private static int compareEntity(EntityObstacle left, EntityObstacle right) {
        int cmp = Long.compare(left.sourceId(), right.sourceId());
        if (cmp != 0) return cmp;
        cmp = Long.compare(left.primitiveId(), right.primitiveId());
        if (cmp != 0) return cmp;
        return Long.compare(left.motion().continuityEpoch(), right.motion().continuityEpoch());
    }

    private static int compareSphere(SphereObstacle left, SphereObstacle right) {
        int cmp = compareVec(
                left.sphere().center(),
                right.sphere().center());
        if (cmp != 0) return cmp;
        return Double.compare(
                left.sphere().radius(), right.sphere().radius());
    }

    static int compareBlockPos(BlockPos left, BlockPos right) {
        int cmp = Integer.compare(left.getX(), right.getX());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(left.getY(), right.getY());
        if (cmp != 0) return cmp;
        return Integer.compare(left.getZ(), right.getZ());
    }

    private static int compareAabb(Aabb3d left, Aabb3d right) {
        int cmp = Double.compare(left.minX(), right.minX());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.minY(), right.minY());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.minZ(), right.minZ());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.maxX(), right.maxX());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.maxY(), right.maxY());
        if (cmp != 0) return cmp;
        return Double.compare(left.maxZ(), right.maxZ());
    }

    private static int compareVec(Vector3dc left, Vector3dc right) {
        int cmp = Double.compare(left.x(), right.x());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.y(), right.y());
        if (cmp != 0) return cmp;
        return Double.compare(left.z(), right.z());
    }
}
