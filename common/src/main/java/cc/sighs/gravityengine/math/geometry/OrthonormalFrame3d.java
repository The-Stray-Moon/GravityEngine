package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Immutable orthonormal basis with direct world/local transforms.
 *
 * <p>This type is the single generic orthogonal reference-frame math
 * primitive in GravityEngine.  Domain frames (gravity, collision geometry,
 * body-attitude look reference) may keep their own metadata and axis
 * conventions, but every world/local transform is executed here exactly
 * once; Minecraft-facing types only marshal coordinates into and out of the
 * {@code Vector3d}/{@code Vector3dc} API.</p>
 */
public final class OrthonormalFrame3d {
    /** Canonical immutable world frame. Destination-based accessors never expose its storage. */
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

    public OrthonormalFrame3d(Vector3dc axisX, Vector3dc axisY, Vector3dc axisZ) {
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

    public Vector3d axisX(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(this.xx, this.xy, this.xz);
    }

    public Vector3d axisY(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(this.yx, this.yy, this.yz);
    }

    public Vector3d axisZ(Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(this.zx, this.zy, this.zz);
    }

    public Vector3d worldToLocal(Vector3dc world, Vector3d dest) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dest, "dest");
        requireFinite(world, "world");
        double x = world.x();
        double y = world.y();
        double z = world.z();
        return dest.set(
                x * this.xx + y * this.xy + z * this.xz,
                x * this.yx + y * this.yy + z * this.yz,
                x * this.zx + y * this.zy + z * this.zz
        );
    }

    public Vector3d localToWorld(Vector3dc local, Vector3d dest) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(dest, "dest");
        requireFinite(local, "local");
        double x = local.x();
        double y = local.y();
        double z = local.z();
        return dest.set(
                this.xx * x + this.yx * y + this.zx * z,
                this.xy * x + this.yy * y + this.zy * z,
                this.xz * x + this.yz * y + this.zz * z
        );
    }

    /**
     * Exact component-wise value semantics.  The frame is immutable and all
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

    Vector3d localToWorld(double x, double y, double z, Vector3d dest) {
        return Objects.requireNonNull(dest, "dest").set(
                this.xx * x + this.yx * y + this.zx * z,
                this.xy * x + this.yy * y + this.zy * z,
                this.xz * x + this.yz * y + this.zz * z
        );
    }

    double axisXDot(Vector3dc vector) {
        return this.xx * vector.x() + this.xy * vector.y() + this.xz * vector.z();
    }

    double axisYDot(Vector3dc vector) {
        return this.yx * vector.x() + this.yy * vector.y() + this.yz * vector.z();
    }

    double axisZDot(Vector3dc vector) {
        return this.zx * vector.x() + this.zy * vector.y() + this.zz * vector.z();
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

    public Vector3d worldPointToLocal(Vector3dc worldPoint, Vector3dc origin, Vector3d dest) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(dest, "dest");
        requireFinite(worldPoint, "worldPoint");
        requireFinite(origin, "origin");
        dest.set(worldPoint).sub(origin);
        return worldToLocal(dest, dest);
    }

    public Vector3d localPointToWorld(Vector3dc localPoint, Vector3dc origin, Vector3d dest) {
        Objects.requireNonNull(origin, "origin");
        requireFinite(origin, "origin");
        localToWorld(localPoint, dest);
        return dest.add(origin);
    }

    private static void requireUnit(Vector3dc vector, String name) {
        double lengthSquared = vector.lengthSquared();
        if (Math.abs(lengthSquared - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(name + " must have unit length: " + vector);
        }
    }

    private static void requireUnit(double x, double y, double z, String name) {
        double lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(name + " must have unit length");
        }
    }

    private static void requireOrthogonal(
            Vector3dc first,
            Vector3dc second,
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
        if (Math.abs(ax * bx + ay * by + az * bz) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    firstName + " and " + secondName + " must be orthogonal"
            );
        }
    }

    /**
     * Rejects reflected orthonormal bases (determinant -1).  Only proper
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

    static void requireFinite(Vector3dc vector, String name) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite: " + vector);
        }
    }
}
