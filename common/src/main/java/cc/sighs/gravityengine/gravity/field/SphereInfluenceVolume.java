package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityFieldBounds;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;
import java.util.Optional;

/** Finite spherical influence volume. */
public record SphereInfluenceVolume(
        Vec3d center,
        double radius
) implements GravityInfluenceVolume {

    public SphereInfluenceVolume {
        Objects.requireNonNull(center, "center");
        requireFinite(center, "center");
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException(
                    "radius must be finite and non-negative: " + radius);
        }
    }

    @Override
    public boolean contains(Vec3d position) {
        Objects.requireNonNull(position, "position");
        Vec3d offset = position.subtract(center);
        return offset.lengthSquared() <= radius * radius;
    }

    @Override
    public Optional<GravityFieldBounds> finiteBounds() {
        return Optional.of(new GravityFieldBounds(
                center.x() - radius,
                center.y() - radius,
                center.z() - radius,
                center.x() + radius,
                center.y() + radius,
                center.z() + radius
        ));
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
