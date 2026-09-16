package cc.sighs.gravityengine.gravity.collision;

import java.util.List;

/** Narrow Minecraft discovery/publication seam. A cross-thread adapter reads its
 * atomic latest COMPLETED publication once here. It never exposes a foreign body,
 * lock or deferred callback. Same-thread sources may construct this value here.
 * Local geometry is explicitly exact; Vanilla broad-phase AABBs cannot opt into rotation.
 * The entity's discovery bounds must enclose all published primitives at t=0. */
public interface CollisionSurfaceMotionProvider {
    MotionSnapshot gravityengine$collisionSurfaceMotionSnapshot();

    record MotionSnapshot(List<DynamicCollisionObstacleSnapshot> primitives,
                          double declaredMaximumPointDisplacement) {
        public MotionSnapshot {
            primitives = List.copyOf(primitives);
            if (primitives.isEmpty() || !Double.isFinite(declaredMaximumPointDisplacement)
                    || declaredMaximumPointDisplacement < 0)
                throw new IllegalArgumentException("invalid rigid publication/discovery bound");
        }
    }
}
