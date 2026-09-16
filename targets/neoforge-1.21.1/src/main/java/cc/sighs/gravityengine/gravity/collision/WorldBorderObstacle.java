package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/** Static world-border collision primitive with neutral geometry only. */
public record WorldBorderObstacle(Aabb3d bounds) implements CollisionObstacle {
    public WorldBorderObstacle {
        Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public Aabb3d broadphaseBounds() { return bounds; }

    @Override
    public Vector3d velocityAt(Vector3dc worldPoint, double time) { return new Vector3d(); }
}
