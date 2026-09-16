package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Immutable entity obstacle: a translated exact capsule or a prescribed rigid OBB.
 * Captured gravity geometry owns capsule axis; actor/model attitude is absent.
 * Source/primitive identity and motion do not depend on Entity. */
public record EntityObstacle(DynamicCollisionObstacleSnapshot snapshot, boolean exactGeometry) implements CollisionObstacle {
    public EntityObstacle(DynamicCollisionObstacleSnapshot snapshot) { this(snapshot, true); }
    public EntityObstacle {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        if (!exactGeometry && snapshot.motion().rotating())
            throw new CollisionSceneCoverageException("rotating obstacle requires exact local geometry");
    }
    public EntityObstacle(int id, cc.sighs.gravityengine.math.geometry.Aabb3d bounds) { this(id, bounds, null, new Vector3d()); }
    public EntityObstacle(int id, cc.sighs.gravityengine.math.geometry.Aabb3d bounds, CollisionBody exact) { this(id, bounds, exact, new Vector3d()); }
    /** Static/translation adapter. AABB geometry is valid only in this zero-angular subset. */
    public EntityObstacle(int id, cc.sighs.gravityengine.math.geometry.Aabb3d bounds, CollisionBody exact, Vector3d intervalDisplacement) {
        this(new DynamicCollisionObstacleSnapshot(id, 0,
                (exact == null ? OrientedBox.axisAligned(bounds) : exact).move(bounds.center().negate()),
                new RigidMotionSnapshot(new cc.sighs.gravityengine.math.geometry.RigidPose(bounds.center(), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY),
                        intervalDisplacement, new Vector3d(), 0, 0, 0, 1)), exact != null);
    }
    public long sourceId() { return snapshot.sourceId(); }
    public long primitiveId() { return snapshot.primitiveId(); }
    public RigidMotionSnapshot motion() { return snapshot.motion(); }
    public cc.sighs.gravityengine.math.geometry.Aabb3d bounds() { return snapshot.initialBounds(); }
    public CollisionBody exactBodyAt(double time) { return snapshot.exactBodyAt(time); }
    public OrientedBox bodyAt(double time) { return snapshot.bodyAt(time); }
    public cc.sighs.gravityengine.math.geometry.Aabb3d sweptBounds(double lo, double hi) { return snapshot.sweptBounds(lo, hi); }
    @Override public cc.sighs.gravityengine.math.geometry.Aabb3d broadphaseBounds() { return bounds(); }
    @Override public Vector3d velocityAt(Vector3dc point, double time) { return motion().velocityAt(point, time); }
}
