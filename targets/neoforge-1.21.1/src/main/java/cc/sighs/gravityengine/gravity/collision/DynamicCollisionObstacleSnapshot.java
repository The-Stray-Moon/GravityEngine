package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/** Immutable exact primitive and completed motion. Ordinary capsules retain their
 * captured gravity axis and publish translation only; prescribed external OBBs may rotate.
 * Body ids are adapter-assigned
 * stable ids, not necessarily entity ids. Replacement changes id or continuity epoch.
 * Cached bounds are conservative query evidence, never exact occupancy. */
public final class DynamicCollisionObstacleSnapshot {
    private final long sourceId, primitiveId;
    private final CollisionBody exactLocalBody;
    private final RigidMotionSnapshot motion;
    private final Aabb3d initialBounds, operationSweptBounds;

    public DynamicCollisionObstacleSnapshot(long sourceId, long primitiveId,
            CollisionBody localBody, RigidMotionSnapshot motion) {
        this.sourceId = sourceId; this.primitiveId = primitiveId;
        this.exactLocalBody = Objects.requireNonNull(localBody, "localBody");
        this.motion = Objects.requireNonNull(motion, "motion");
        if (!(localBody instanceof OrientedBox) && (motion.rotating()
                || !motion.start().orientation().equals(cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY)))
            throw new IllegalArgumentException("non-rigid exact entity geometry requires translation-only publication");
        this.initialBounds = exactBodyAt(0).enclosingAabb();
        this.operationSweptBounds = sweptBounds(0, 1);
    }
    public long sourceId() { return sourceId; }
    public long primitiveId() { return primitiveId; }
    /** Only the prescribed OBB motion kernel may request a rigid primitive. */
    public OrientedBox localBody() {
        if (exactLocalBody instanceof OrientedBox box) return box;
        throw new IllegalStateException("exact character is not a rigid OBB");
    }
    public CollisionBody exactBodyAt(double time) {
        return exactLocalBody instanceof OrientedBox box ? motion.bodyAt(box, time)
                : exactLocalBody.move(motion.poseAt(time).center());
    }
    public double maximumPointDisplacement() {
        return hasRigidBox() ? motion.maximumPointDisplacement(localBody()) : motion.displacementForInterval().length();
    }
    public boolean hasRigidBox() { return exactLocalBody instanceof OrientedBox; }
    public RigidMotionSnapshot motion() { return motion; }
    public OrientedBox bodyAt(double time) { return motion.bodyAt(localBody(), time); }
    public Aabb3d initialBounds() { return initialBounds; }
    public Aabb3d operationSweptBounds() { return operationSweptBounds; }
    public Aabb3d sweptBounds(double lo, double hi) {
        if (lo == 0 && hi == 1 && operationSweptBounds != null) return operationSweptBounds;
        return exactLocalBody instanceof OrientedBox box ? motion.sweptBounds(box, lo, hi)
                : exactBodyAt(lo).enclosingAabb().expandTowards(motion.centerDisplacement(lo, hi));
    }
    @Override public boolean equals(Object other) {
        return other instanceof DynamicCollisionObstacleSnapshot b && sourceId == b.sourceId
                && primitiveId == b.primitiveId && exactLocalBody.equals(b.exactLocalBody) && motion.equals(b.motion);
    }
    @Override public int hashCode() { return Objects.hash(sourceId, primitiveId, exactLocalBody, motion); }
}
