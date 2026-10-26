package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/**
 * Static block collision primitive.
 *
 * <p>{@code blockPos} is opaque Minecraft provenance carried for deterministic
 * ordering and diagnostics; the kernel consumes only the neutral
 * {@link Aabb3d} geometry.</p>
 */
public record BlockObstacle(CellPos blockPos, Aabb3d bounds) implements CollisionObstacle {
    public BlockObstacle {
        Objects.requireNonNull(blockPos, "blockPos");
        Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public Aabb3d broadphaseBounds() { return bounds; }

    @Override
    public Vec3d velocityAt(Vec3d worldPoint, double time) { return Vec3d.ZERO; }
}
