package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.Sphere3d;
import java.util.Objects;

/**
 * Generic spherical collision obstacle.
 *
 * <p>The obstacle owns only {@link Sphere3d} geometry.  It has no gravity
 * source identity; when the scene builder needs per-source deduplication it
 * keeps a separate registration-layer map keyed by the source key.</p>
 */
public record SphereObstacle(Sphere3d sphere)
        implements CollisionObstacle {

    public SphereObstacle {
        Objects.requireNonNull(sphere, "sphere");
    }

    /** Fresh world-space center copy for neutral consumers. */
    public Vec3d center() {
        return sphere.center();
    }

    @Override
    public Aabb3d broadphaseBounds() {
        double r = sphere.radius();
        Vec3d c = sphere.center();
        return new Aabb3d(
                c.x() - r, c.y() - r, c.z() - r,
                c.x() + r, c.y() + r, c.z() + r);
    }

    @Override
    public Vec3d velocityAt(Vec3d worldPoint, double time) {
        return Vec3d.ZERO;
    }
}
