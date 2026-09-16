package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Immutable pure sphere geometry: center and non-negative radius only.
 *
 * <p>This type owns geometry data and nothing else.  Contact and sweep
 * algorithms live in the collision kernel ({@code SphereObbCollision}); no
 * Minecraft type may appear here.</p>
 */
public final class Sphere3d {
    private final Vector3d center;
    private final double radius;

    public Sphere3d(Vector3dc center, double radius) {
        Objects.requireNonNull(center, "center");
        if (!Double.isFinite(center.x())
                || !Double.isFinite(center.y())
                || !Double.isFinite(center.z())) {
            throw new IllegalArgumentException(
                    "center must be finite: " + center);
        }
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException(
                    "radius must be finite and non-negative: " + radius);
        }
        this.center = new Vector3d(center);
        this.radius = radius;
    }

    /** Defensive world-space center copy. */
    public Vector3d center() {
        return new Vector3d(center);
    }

    public double radius() {
        return radius;
    }

    /** Immutable copy translated by a world-space displacement. */
    public Sphere3d moved(Vector3dc displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new Sphere3d(
                new Vector3d(center).add(displacement), radius);
    }

    public Vector3d center(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(center);
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
