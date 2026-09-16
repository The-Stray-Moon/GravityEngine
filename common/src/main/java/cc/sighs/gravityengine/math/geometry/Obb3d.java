package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/** Immutable centered OBB represented by half extents and three orthonormal basis axes. */
public final class Obb3d {
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double halfX;
    private final double halfY;
    private final double halfZ;
    private final OrthonormalFrame3d frame;

    public Obb3d(
            double centerX,
            double centerY,
            double centerZ,
            double halfX,
            double halfY,
            double halfZ,
            OrthonormalFrame3d frame
    ) {
        requireFinite(centerX, "centerX");
        requireFinite(centerY, "centerY");
        requireFinite(centerZ, "centerZ");
        requireFinite(halfX, "halfX");
        requireFinite(halfY, "halfY");
        requireFinite(halfZ, "halfZ");
        if (halfX < 0.0D || halfY < 0.0D || halfZ < 0.0D) {
            throw new IllegalArgumentException("half extents must be non-negative");
        }
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.halfX = halfX;
        this.halfY = halfY;
        this.halfZ = halfZ;
        this.frame = Objects.requireNonNull(frame, "frame");
    }

    public Obb3d(
            Vector3dc center,
            Vector3dc halfExtents,
            Vector3dc axisX,
            Vector3dc axisY,
            Vector3dc axisZ
    ) {
        this(center, halfExtents, new OrthonormalFrame3d(axisX, axisY, axisZ));
    }

    public Obb3d(Vector3dc center, Vector3dc halfExtents, OrthonormalFrame3d frame) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtents, "halfExtents");
        this.frame = Objects.requireNonNull(frame, "frame");
        OrthonormalFrame3d.requireFinite(center, "center");
        OrthonormalFrame3d.requireFinite(halfExtents, "halfExtents");
        if (halfExtents.x() < 0.0D || halfExtents.y() < 0.0D || halfExtents.z() < 0.0D) {
            throw new IllegalArgumentException("halfExtents must be non-negative: " + halfExtents);
        }
        this.centerX = center.x();
        this.centerY = center.y();
        this.centerZ = center.z();
        this.halfX = halfExtents.x();
        this.halfY = halfExtents.y();
        this.halfZ = halfExtents.z();
    }

    public Vector3d center(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(
                this.centerX, this.centerY, this.centerZ
        );
    }

    public Vector3d halfExtents(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(this.halfX, this.halfY, this.halfZ);
    }

    public Vector3d axisX(Vector3d dest) { return this.frame.axisX(dest); }
    public Vector3d axisY(Vector3d dest) { return this.frame.axisY(dest); }
    public Vector3d axisZ(Vector3d dest) { return this.frame.axisZ(dest); }

    /** The immutable local frame (axisX/axisY/axisZ = local +X/+Y/+Z). */
    public OrthonormalFrame3d frame() {
        return this.frame;
    }

    public Vector3d corner(double sx, double sy, double sz, Vector3d dest) {
        requireFinite(sx, "sx");
        requireFinite(sy, "sy");
        requireFinite(sz, "sz");
        Objects.requireNonNull(dest, "dest");
        this.frame.localToWorld(
                this.halfX * sx,
                this.halfY * sy,
                this.halfZ * sz,
                dest
        );
        return dest.add(this.centerX, this.centerY, this.centerZ);
    }

    public double radiusAlong(Vector3dc unitAxis) {
        Objects.requireNonNull(unitAxis, "unitAxis");
        OrthonormalFrame3d.requireFinite(unitAxis, "unitAxis");
        if (Math.abs(unitAxis.lengthSquared() - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException("unitAxis must have unit length: " + unitAxis);
        }
        return radiusAlongUnitUnchecked(unitAxis);
    }

    double radiusAlongUnitUnchecked(Vector3dc unitAxis) {
        double radius = this.halfX * Math.abs(this.frame.axisXDot(unitAxis))
                + this.halfY * Math.abs(this.frame.axisYDot(unitAxis))
                + this.halfZ * Math.abs(this.frame.axisZDot(unitAxis));
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException("projected radius must be finite and non-negative");
        }
        return radius;
    }

    public Obb3d moved(Vector3dc displacement) {
        Objects.requireNonNull(displacement, "displacement");
        OrthonormalFrame3d.requireFinite(displacement, "displacement");
        return new Obb3d(
                this.centerX + displacement.x(),
                this.centerY + displacement.y(),
                this.centerZ + displacement.z(),
                this.halfX,
                this.halfY,
                this.halfZ,
                this.frame
        );
    }

    double centerX() { return this.centerX; }
    double centerY() { return this.centerY; }
    double centerZ() { return this.centerZ; }
    double halfX() { return this.halfX; }
    double halfY() { return this.halfY; }
    double halfZ() { return this.halfZ; }
    double worldExtentX() { return this.frame.extentAlongWorldX(this.halfX, this.halfY, this.halfZ); }
    double worldExtentY() { return this.frame.extentAlongWorldY(this.halfX, this.halfY, this.halfZ); }
    double worldExtentZ() { return this.frame.extentAlongWorldZ(this.halfX, this.halfY, this.halfZ); }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
