package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Comparator;

/**
 * Provenance-preserving sealed collision obstacle model.
 *
 * <p>The kernel-facing geometry surface is neutral ({@link Aabb3d} plus
 * immutable {@link Vec3d} values). Minecraft-specific values such as {@link CellPos} and entity
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

    /** Immutable material-point velocity in blocks/tick at an absolute normalized operation time. */
    Vec3d velocityAt(Vec3d worldPoint, double time);

    private static int compareStable(CollisionObstacle left, CollisionObstacle right) {
        int cmp = Integer.compare(typeOrder(left), typeOrder(right));
        if (cmp != 0) return cmp;
        if (left instanceof BlockObstacle a) {
            return compareBlock(a, (BlockObstacle) right);
        }
        if (left instanceof EntityObstacle a) {
            return compareEntity(a, (EntityObstacle) right);
        }
        if (left instanceof WorldBorderObstacle a) {
            return compareAabb(a.bounds(), ((WorldBorderObstacle) right).bounds());
        }
        if (left instanceof SphereObstacle a) {
            return compareSphere(a, (SphereObstacle) right);
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + left.getClass().getName()
        );
    }

    private static int typeOrder(CollisionObstacle obstacle) {
        if (obstacle instanceof BlockObstacle) {
            return 0;
        }
        if (obstacle instanceof EntityObstacle) {
            return 1;
        }
        if (obstacle instanceof WorldBorderObstacle) {
            return 2;
        }
        if (obstacle instanceof SphereObstacle) {
            return 3;
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    private static int compareBlock(BlockObstacle left, BlockObstacle right) {
        int cmp = compareBlockPos(left.blockPos(), right.blockPos());
        if (cmp != 0) return cmp;
        return compareAabb(left.bounds(), right.bounds());
    }

    private static int compareEntity(EntityObstacle left, EntityObstacle right) {
        int cmp = left.providerNamespace()
                .compareTo(right.providerNamespace());
        if (cmp != 0) return cmp;
        cmp = Long.compare(left.snapshot().providerRegistrationEpoch(), right.snapshot().providerRegistrationEpoch());
        if (cmp != 0) return cmp;
        cmp = Long.compare(left.sourceId(), right.sourceId());
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

    static int compareBlockPos(CellPos left, CellPos right) {
        int cmp = Integer.compare(left.x(), right.x());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(left.y(), right.y());
        if (cmp != 0) return cmp;
        return Integer.compare(left.z(), right.z());
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

    private static int compareVec(Vec3d left, Vec3d right) {
        int cmp = Double.compare(left.x(), right.x());
        if (cmp != 0) return cmp;
        cmp = Double.compare(left.y(), right.y());
        if (cmp != 0) return cmp;
        return Double.compare(left.z(), right.z());
    }
}
