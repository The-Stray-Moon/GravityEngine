package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable orthonormal basis with direct world/local transforms.
 *
 * <p>This type is the single generic orthogonal reference-frame math
 * primitive in GravityEngine. Domain frames (gravity, collision geometry,
 * body-attitude look reference) may keep their own metadata and axis
 * conventions, but every world/local transform is executed here exactly
 * once.</p>
 */
public final class OrthonormalFrame3d {
    /** Canonical immutable world frame. */
    public static final OrthonormalFrame3d IDENTITY = new OrthonormalFrame3d(
            1.0D, 0.0D, 0.0D,
            0.0D, 1.0D, 0.0D,
            0.0D, 0.0D, 1.0D
    );

    private final double xx;
    private final double xy;
    private final double xz;
    private final double yx;
    private final double yy;
    private final double yz;
    private final double zx;
    private final double zy;
    private final double zz;

    public OrthonormalFrame3d(
            double xx, double xy, double xz,
            double yx, double yy, double yz,
            double zx, double zy, double zz
    ) {
        requireFinite(xx, xy, xz, "axisX");
        requireFinite(yx, yy, yz, "axisY");
        requireFinite(zx, zy, zz, "axisZ");
        requireUnit(xx, xy, xz, "axisX");
        requireUnit(yx, yy, yz, "axisY");
        requireUnit(zx, zy, zz, "axisZ");
        requireOrthogonal(xx, xy, xz, yx, yy, yz, "axisX", "axisY");
        requireOrthogonal(xx, xy, xz, zx, zy, zz, "axisX", "axisZ");
        requireOrthogonal(yx, yy, yz, zx, zy, zz, "axisY", "axisZ");
        requireProperRotation(
                xx, xy, xz, yx, yy, yz, zx, zy, zz);
        this.xx = xx;
        this.xy = xy;
        this.xz = xz;
        this.yx = yx;
        this.yy = yy;
        this.yz = yz;
        this.zx = zx;
        this.zy = zy;
        this.zz = zz;
    }

    public OrthonormalFrame3d(Vec3d axisX, Vec3d axisY, Vec3d axisZ) {
        Objects.requireNonNull(axisX, "axisX");
        Objects.requireNonNull(axisY, "axisY");
        Objects.requireNonNull(axisZ, "axisZ");
        requireFinite(axisX, "axisX");
        requireFinite(axisY, "axisY");
        requireFinite(axisZ, "axisZ");
        requireUnit(axisX, "axisX");
        requireUnit(axisY, "axisY");
        requireUnit(axisZ, "axisZ");
        requireOrthogonal(axisX, axisY, "axisX", "axisY");
        requireOrthogonal(axisX, axisZ, "axisX", "axisZ");
        requireOrthogonal(axisY, axisZ, "axisY", "axisZ");
        requireProperRotation(
                axisX.x(), axisX.y(), axisX.z(),
                axisY.x(), axisY.y(), axisY.z(),
                axisZ.x(), axisZ.y(), axisZ.z());
        this.xx = axisX.x();
        this.xy = axisX.y();
        this.xz = axisX.z();
        this.yx = axisY.x();
        this.yy = axisY.y();
        this.yz = axisY.z();
        this.zx = axisZ.x();
        this.zy = axisZ.y();
        this.zz = axisZ.z();
    }

    public Vec3d axisX() {
        return new Vec3d(this.xx, this.xy, this.xz);
    }

    public Vec3d axisY() {
        return new Vec3d(this.yx, this.yy, this.yz);
    }

    public Vec3d axisZ() {
        return new Vec3d(this.zx, this.zy, this.zz);
    }

    void writeAxes(
            double[] target,
            int offset
    ) {
        Objects.requireNonNull(target, "target");

        if (offset < 0 || offset + 9 > target.length) {
            throw new IndexOutOfBoundsException(offset);
        }

        target[offset] = this.xx;
        target[offset + 1] = this.xy;
        target[offset + 2] = this.xz;

        target[offset + 3] = this.yx;
        target[offset + 4] = this.yy;
        target[offset + 5] = this.yz;

        target[offset + 6] = this.zx;
        target[offset + 7] = this.zy;
        target[offset + 8] = this.zz;
    }

    public Vec3d worldToLocal(Vec3d world) {
        Objects.requireNonNull(world, "world");
        requireFinite(world, "world");
        double x = world.x();
        double y = world.y();
        double z = world.z();
        return new Vec3d(
                x * this.xx + y * this.xy + z * this.xz,
                x * this.yx + y * this.yy + z * this.yz,
                x * this.zx + y * this.zy + z * this.zz
        );
    }

    public Vec3d localToWorld(Vec3d local) {
        Objects.requireNonNull(local, "local");
        requireFinite(local, "local");
        double x = local.x();
        double y = local.y();
        double z = local.z();
        return new Vec3d(
                this.xx * x + this.yx * y + this.zx * z,
                this.xy * x + this.yy * y + this.zy * z,
                this.xz * x + this.yz * y + this.zz * z
        );
    }

    Vec3d localToWorld(double x, double y, double z) {
        return new Vec3d(
                this.xx * x + this.yx * y + this.zx * z,
                this.xy * x + this.yy * y + this.zy * z,
                this.xz * x + this.yz * y + this.zz * z
        );
    }

    Vec3d localPointToWorld(
            double x,
            double y,
            double z,
            double originX,
            double originY,
            double originZ
    ) {
        return new Vec3d(
                this.xx * x + this.yx * y + this.zx * z + originX,
                this.xy * x + this.yy * y + this.zy * z + originY,
                this.xz * x + this.yz * y + this.zz * z + originZ
        );
    }

    double axisXDot(Vec3d vector) {
        return axisXDot(
                vector.x(),
                vector.y(),
                vector.z()
        );
    }

    double axisYDot(Vec3d vector) {
        return axisYDot(
                vector.x(),
                vector.y(),
                vector.z()
        );
    }

    double axisZDot(Vec3d vector) {
        return axisZDot(
                vector.x(),
                vector.y(),
                vector.z()
        );
    }

    double axisXDot(
            double x,
            double y,
            double z
    ) {
        return this.xx * x
                + this.xy * y
                + this.xz * z;
    }

    double axisYDot(
            double x,
            double y,
            double z
    ) {
        return this.yx * x
                + this.yy * y
                + this.yz * z;
    }

    double axisZDot(
            double x,
            double y,
            double z
    ) {
        return this.zx * x
                + this.zy * y
                + this.zz * z;
    }

    double extentAlongWorldX(double halfX, double halfY, double halfZ) {
        return halfX * Math.abs(this.xx)
                + halfY * Math.abs(this.yx)
                + halfZ * Math.abs(this.zx);
    }

    double extentAlongWorldY(double halfX, double halfY, double halfZ) {
        return halfX * Math.abs(this.xy)
                + halfY * Math.abs(this.yy)
                + halfZ * Math.abs(this.zy);
    }

    double extentAlongWorldZ(double halfX, double halfY, double halfZ) {
        return halfX * Math.abs(this.xz)
                + halfY * Math.abs(this.yz)
                + halfZ * Math.abs(this.zz);
    }

    public Vec3d worldPointToLocal(Vec3d worldPoint, Vec3d origin) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        Objects.requireNonNull(origin, "origin");
        requireFinite(worldPoint, "worldPoint");
        requireFinite(origin, "origin");
        return worldPointToLocal(
                worldPoint,
                origin.x(),
                origin.y(),
                origin.z()
        );
    }

    Vec3d worldPointToLocal(
            Vec3d worldPoint,
            double originX,
            double originY,
            double originZ
    ) {
        double x = worldPoint.x() - originX;
        double y = worldPoint.y() - originY;
        double z = worldPoint.z() - originZ;
        return new Vec3d(
                x * this.xx + y * this.xy + z * this.xz,
                x * this.yx + y * this.yy + z * this.yz,
                x * this.zx + y * this.zy + z * this.zz
        );
    }

    public Vec3d localPointToWorld(Vec3d localPoint, Vec3d origin) {
        Objects.requireNonNull(localPoint, "localPoint");
        Objects.requireNonNull(origin, "origin");
        requireFinite(localPoint, "localPoint");
        requireFinite(origin, "origin");
        return localPointToWorld(
                localPoint.x(),
                localPoint.y(),
                localPoint.z(),
                origin.x(),
                origin.y(),
                origin.z()
        );
    }

    /** Fused local-point transform without an intermediate local vector. */
    public Vec3d localPointToWorld(
            double x,
            double y,
            double z,
            Vec3d origin
    ) {
        Objects.requireNonNull(origin, "origin");
        requireFinite(x, y, z, "localPoint");
        requireFinite(origin, "origin");
        return localPointToWorld(
                x,
                y,
                z,
                origin.x(),
                origin.y(),
                origin.z()
        );
    }

    /**
     * Exact component-wise value semantics. The frame is immutable and all
     * nine axis components are stored exactly as supplied, so bit equality
     * matches the value equality of the doubles that produced the frame.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OrthonormalFrame3d that)) {
            return false;
        }
        return Double.compare(this.xx, that.xx) == 0
                && Double.compare(this.xy, that.xy) == 0
                && Double.compare(this.xz, that.xz) == 0
                && Double.compare(this.yx, that.yx) == 0
                && Double.compare(this.yy, that.yy) == 0
                && Double.compare(this.yz, that.yz) == 0
                && Double.compare(this.zx, that.zx) == 0
                && Double.compare(this.zy, that.zy) == 0
                && Double.compare(this.zz, that.zz) == 0;
    }

    @Override
    public int hashCode() {
        int result = hash(this.xx);
        result = 31 * result + hash(this.xy);
        result = 31 * result + hash(this.xz);
        result = 31 * result + hash(this.yx);
        result = 31 * result + hash(this.yy);
        result = 31 * result + hash(this.yz);
        result = 31 * result + hash(this.zx);
        result = 31 * result + hash(this.zy);
        return 31 * result + hash(this.zz);
    }

    private static int hash(double value) {
        long bits = Double.doubleToLongBits(value);
        return (int) (bits ^ (bits >>> 32));
    }

    private static void requireUnit(Vec3d vector, String name) {
        double lengthSquared = vector.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || Math.abs(lengthSquared - 1.0D)
                > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    name + " must have unit length: " + vector);
        }
    }

    private static void requireUnit(double x, double y, double z, String name) {
        double lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(name + " must have unit length");
        }
    }

    private static void requireOrthogonal(
            Vec3d first,
            Vec3d second,
            String firstName,
            String secondName
    ) {
        if (Math.abs(first.dot(second)) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    firstName + " and " + secondName + " must be orthogonal"
            );
        }
    }

    private static void requireOrthogonal(
            double ax, double ay, double az,
            double bx, double by, double bz,
            String firstName,
            String secondName
    ) {
        if (Math.abs(ax * bx + ay * by + az * bz)
                > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    firstName + " and " + secondName + " must be orthogonal"
            );
        }
    }

    /**
     * Rejects reflected orthonormal bases (determinant -1). Only proper
     * rotations are representable as quaternions, so a frame must satisfy
     * {@code det = X dot (Y cross Z) = +1} within the same
     * {@link GeometryTolerance#ORTHONORMAL} band used for unit length and
     * orthogonality.
     */
    private static void requireProperRotation(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double cx, double cy, double cz
    ) {
        double determinant = ax * (by * cz - bz * cy)
                - ay * (bx * cz - bz * cx)
                + az * (bx * cy - by * cx);
        if (!Double.isFinite(determinant)
                || Math.abs(determinant - 1.0D)
                > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    "frame axes must form a proper right-handed rotation "
                            + "(det = " + determinant + ")");
        }
    }

    private static void requireFinite(double x, double y, double z, String name) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(name + " must be finite: " + vector);
        }
    }
}
