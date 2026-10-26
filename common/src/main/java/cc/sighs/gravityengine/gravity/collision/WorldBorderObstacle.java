package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/** Static world-border collision primitive with neutral geometry only. */
public record WorldBorderObstacle(Aabb3d bounds) implements CollisionObstacle {
    public WorldBorderObstacle {
        Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public Aabb3d broadphaseBounds() { return bounds; }

    @Override
    public Vec3d velocityAt(Vec3d worldPoint, double time) { return Vec3d.ZERO; }
}
