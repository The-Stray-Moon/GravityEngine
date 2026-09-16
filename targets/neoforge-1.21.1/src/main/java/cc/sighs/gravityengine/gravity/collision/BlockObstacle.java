package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Static block collision primitive.
 *
 * <p>{@code blockPos} is opaque Minecraft provenance carried for deterministic
 * ordering and diagnostics; the kernel consumes only the neutral
 * {@link Aabb3d} geometry.</p>
 */
public record BlockObstacle(BlockPos blockPos, Aabb3d bounds) implements CollisionObstacle {
    public BlockObstacle {
        Objects.requireNonNull(blockPos, "blockPos");
        Objects.requireNonNull(bounds, "bounds");
        blockPos = blockPos.immutable();
    }

    @Override
    public Aabb3d broadphaseBounds() { return bounds; }

    @Override
    public Vector3d velocityAt(Vector3dc worldPoint, double time) { return new Vector3d(); }
}
