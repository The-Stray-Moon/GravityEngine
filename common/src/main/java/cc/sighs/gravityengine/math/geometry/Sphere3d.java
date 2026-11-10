package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable pure sphere geometry: center and non-negative radius only.
 *
 * <p>This type owns geometry data and nothing else.  Contact and sweep
 * algorithms live in the collision kernel ({@code SphereObbCollision}); no
 * Minecraft type may appear here.</p>
 */
public final class Sphere3d {
    private final Vec3d center;
    private final double radius;

    public Sphere3d(Vec3d center, double radius) {
        Objects.requireNonNull(center, "center");
        if (!center.isFinite()) {
            throw new IllegalArgumentException(
                    "center must be finite: " + center);
        }
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException(
                    "radius must be finite and non-negative: " + radius);
        }
        this.center = center;
        this.radius = radius;
    }

    public Vec3d center() {
        return center;
    }

    public double radius() {
        return radius;
    }

    /** Immutable copy translated by a world-space displacement. */
    public Sphere3d moved(Vec3d displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new Sphere3d(
                center.add(displacement), radius);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Sphere3d that)) {
            return false;
        }
        return this.radius == that.radius
                && this.center.equals(that.center);
    }

    @Override
    public int hashCode() {
        int result = this.center.hashCode();
        long bits = Double.doubleToLongBits(this.radius);
        return 31 * result + (int) (bits ^ (bits >>> 32));
    }

    @Override
    public String toString() {
        return "Sphere3d[center=" + center + ", radius=" + radius + "]";
    }
}
