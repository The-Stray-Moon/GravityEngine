package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;
import java.util.Optional;

/** Finite spherical influence volume. */
public record SphereInfluenceVolume(
        Vector3d center,
        double radius
) implements GravityInfluenceVolume {

    public SphereInfluenceVolume {
        Objects.requireNonNull(center, "center");
        requireFinite(center, "center");
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException(
                    "radius must be finite and non-negative: " + radius);
        }
        center = new Vector3d(center);
    }

    public SphereInfluenceVolume(Vector3dc center, double radius) {
        this(new Vector3d(center), radius);
    }

    @Override
    public boolean contains(Vector3dc position) {
        Objects.requireNonNull(position, "position");
        Vector3d offset = new Vector3d(position).sub(center);
        return offset.lengthSquared() <= radius * radius;
    }

    @Override
    public Optional<Aabb3d> finiteBounds() {
        return Optional.of(new Aabb3d(
                center.x - radius,
                center.y - radius,
                center.z - radius,
                center.x + radius,
                center.y + radius,
                center.z + radius
        ));
    }

    @Override
    public Vector3d center() {
        return new Vector3d(center);
    }

    private static void requireFinite(Vector3dc vector, String name) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
