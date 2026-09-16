package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Read-only broadphase boundary used by the pure collision stages.
 *
 * <p>Every call must return the deterministic obstacle snapshot covering the
 * supplied body's complete swept path.</p>
 */
@FunctionalInterface
public interface CollisionObstacleQuery {
    List<CollisionObstacle> query(CollisionBody body, Vector3dc movement);
}
