package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
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
            Vec3d center,
            Vec3d halfExtents,
            Vec3d axisX,
            Vec3d axisY,
            Vec3d axisZ
    ) {
        this(center, halfExtents, new OrthonormalFrame3d(axisX, axisY, axisZ));
    }

    public Obb3d(Vec3d center, Vec3d halfExtents, OrthonormalFrame3d frame) {
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

    public Vec3d center() {
        return new Vec3d(this.centerX, this.centerY, this.centerZ);
    }

    public Vec3d halfExtents() {
        return new Vec3d(this.halfX, this.halfY, this.halfZ);
    }

    public Vec3d axisX() { return this.frame.axisX(); }
    public Vec3d axisY() { return this.frame.axisY(); }
    public Vec3d axisZ() { return this.frame.axisZ(); }

    /** The immutable local frame (axisX/axisY/axisZ = local +X/+Y/+Z). */
    public OrthonormalFrame3d frame() {
        return this.frame;
    }

    public Vec3d corner(double sx, double sy, double sz) {
        requireFinite(sx, "sx");
        requireFinite(sy, "sy");
        requireFinite(sz, "sz");
        return this.frame.localPointToWorld(
                this.halfX * sx,
                this.halfY * sy,
                this.halfZ * sz,
                this.centerX,
                this.centerY,
                this.centerZ
        );
    }

    /** Fused world-point to box-local transform; translation uses center scalars. */
    public Vec3d worldPointToLocal(Vec3d worldPoint) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        OrthonormalFrame3d.requireFinite(worldPoint, "worldPoint");
        return this.frame.worldPointToLocal(
                worldPoint,
                this.centerX,
                this.centerY,
                this.centerZ
        );
    }

    /** Fused box-local to world-point transform; translation uses center scalars. */
    public Vec3d localPointToWorld(Vec3d localPoint) {
        Objects.requireNonNull(localPoint, "localPoint");
        OrthonormalFrame3d.requireFinite(localPoint, "localPoint");
        return this.frame.localPointToWorld(
                localPoint.x(),
                localPoint.y(),
                localPoint.z(),
                this.centerX,
                this.centerY,
                this.centerZ
        );
    }

    /** Fused box-local to world-point transform without an intermediate local vector. */
    public Vec3d localPointToWorld(double x, double y, double z) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        return this.frame.localPointToWorld(
                x,
                y,
                z,
                this.centerX,
                this.centerY,
                this.centerZ
        );
    }

    /**
     * Fused box-local FREE VECTOR transform. Translation is deliberately not
     * applied; only the frame basis contributes.
     */
    public Vec3d localVectorToWorld(double x, double y, double z) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        return this.frame.localToWorld(x, y, z);
    }

    public double radiusAlong(Vec3d unitAxis) {
        Objects.requireNonNull(unitAxis, "unitAxis");
        OrthonormalFrame3d.requireFinite(unitAxis, "unitAxis");
        if (Math.abs(unitAxis.lengthSquared() - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException("unitAxis must have unit length: " + unitAxis);
        }
        return radiusAlongUnitUnchecked(unitAxis);
    }

    double radiusAlongUnitUnchecked(Vec3d unitAxis) {
        return radiusAlongUnitUnchecked(
                unitAxis.x(),
                unitAxis.y(),
                unitAxis.z()
        );
    }

    double radiusAlongUnitUnchecked(
            double x,
            double y,
            double z
    ) {
        double radius =
                this.halfX
                        * Math.abs(this.frame.axisXDot(x, y, z))
                        + this.halfY
                        * Math.abs(this.frame.axisYDot(x, y, z))
                        + this.halfZ
                        * Math.abs(this.frame.axisZDot(x, y, z));

        if (!Double.isFinite(radius)
                || radius < 0.0D) {
            throw new IllegalArgumentException(
                    "projected radius must be finite and non-negative");
        }

        return radius;
    }

    public Obb3d moved(Vec3d displacement) {
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

    public double centerX() { return this.centerX; }
    public double centerY() { return this.centerY; }
    public double centerZ() { return this.centerZ; }
    public double halfX() { return this.halfX; }
    public double halfY() { return this.halfY; }
    public double halfZ() { return this.halfZ; }
    double worldExtentX() { return this.frame.extentAlongWorldX(this.halfX, this.halfY, this.halfZ); }
    double worldExtentY() { return this.frame.extentAlongWorldY(this.halfX, this.halfY, this.halfZ); }
    double worldExtentZ() { return this.frame.extentAlongWorldZ(this.halfX, this.halfY, this.halfZ); }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
