package cc.sighs.gravityengine.math;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable double-precision quaternion.
 *
 * <p>This is an internal GravityEngine mathematical primitive, not supported
 * public API. The multiplication order is the Hamilton product
 * {@code this * other}; {@link #transform(Vec3d)} applies the active rotation
 * {@code q * v * q^-1}, matching the previous JOML convention.</p>
 */
public record Quatd(double x, double y, double z, double w) {
    private static final double SLERP_LINEAR_THRESHOLD = 1.0E-6D;

    public static final Quatd IDENTITY = new Quatd(0.0D, 0.0D, 0.0D, 1.0D);

    public Quatd(Quatd value) {
        this(value.x, value.y, value.z, value.w);
    }

    public boolean isFinite() {
        return Double.isFinite(this.x)
                && Double.isFinite(this.y)
                && Double.isFinite(this.z)
                && Double.isFinite(this.w);
    }

    public double lengthSquared() {
        return this.x * this.x + this.y * this.y
                + this.z * this.z + this.w * this.w;
    }

    public Quatd normalized() {
        if (!isFinite()) {
            throw new IllegalArgumentException(
                    "quaternion must be finite: " + this);
        }
        double lengthSquared = lengthSquared();
        if (!Double.isFinite(lengthSquared) || lengthSquared == 0.0D) {
            throw new IllegalArgumentException(
                    "quaternion must be non-degenerate: " + this);
        }
        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        return new Quatd(
                this.x * inverseLength,
                this.y * inverseLength,
                this.z * inverseLength,
                this.w * inverseLength
        );
    }

    public Quatd conjugate() {
        return new Quatd(-this.x, -this.y, -this.z, this.w);
    }

    /** Dot product, conventionally used for sign-invariant quaternion equivalence. */
    public double dot(Quatd other) {
        Objects.requireNonNull(other, "other");
        return this.x * other.x + this.y * other.y
                + this.z * other.z + this.w * other.w;
    }

    /** Hamilton product {@code this * other}. */
    public Quatd multiply(Quatd other) {
        Objects.requireNonNull(other, "other");
        return new Quatd(
                this.w * other.x + other.w * this.x
                        + this.y * other.z - this.z * other.y,
                this.w * other.y + other.w * this.y
                        + this.z * other.x - this.x * other.z,
                this.w * other.z + other.w * this.z
                        + this.x * other.y - this.y * other.x,
                this.w * other.w - this.x * other.x
                        - this.y * other.y - this.z * other.z
        );
    }

    /**
     * Active rotation of a vector by this quaternion.
     *
     * <p>The optimized formula assumes a unit quaternion, matching the
     * previous JOML transform implementation.</p>
     */
    public Vec3d transform(Vec3d vector) {
        Objects.requireNonNull(vector, "vector");
        double x2 = this.x + this.x;
        double y2 = this.y + this.y;
        double z2 = this.z + this.z;
        double xx = this.x * x2;
        double xy = this.x * y2;
        double xz = this.x * z2;
        double yy = this.y * y2;
        double yz = this.y * z2;
        double zz = this.z * z2;
        double wx = this.w * x2;
        double wy = this.w * y2;
        double wz = this.w * z2;
        return new Vec3d(
                (1.0D - (yy + zz)) * vector.x()
                        + (xy - wz) * vector.y()
                        + (xz + wy) * vector.z(),
                (xy + wz) * vector.x()
                        + (1.0D - (xx + zz)) * vector.y()
                        + (yz - wx) * vector.z(),
                (xz - wy) * vector.x()
                        + (yz + wx) * vector.y()
                        + (1.0D - (xx + yy)) * vector.z()
        );
    }

    public static Quatd fromAxisAngle(
            Vec3d axis,
            double angleRadians
    ) {
        Objects.requireNonNull(axis, "axis");

        if (!axis.isFinite()) {
            throw new IllegalArgumentException(
                    "axis must be finite: " + axis);
        }

        if (!Double.isFinite(angleRadians)) {
            throw new IllegalArgumentException(
                    "angleRadians must be finite: " + angleRadians);
        }

        double lengthSquared = axis.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared == 0.0D) {
            throw new IllegalArgumentException(
                    "axis must be non-degenerate: " + axis);
        }

        double inverseLength =
                1.0D / Math.sqrt(lengthSquared);

        double halfAngle =
                angleRadians * 0.5D;

        double sineOverLength =
                Math.sin(halfAngle) * inverseLength;

        return new Quatd(
                axis.x() * sineOverLength,
                axis.y() * sineOverLength,
                axis.z() * sineOverLength,
                Math.cos(halfAngle)
        );
    }

    public static Quatd rotationX(double angleRadians) {
        double halfAngle = angleRadians * 0.5D;
        return new Quatd(Math.sin(halfAngle), 0.0D, 0.0D, Math.cos(halfAngle));
    }

    public static Quatd rotationY(double angleRadians) {
        double halfAngle = angleRadians * 0.5D;
        return new Quatd(0.0D, Math.sin(halfAngle), 0.0D, Math.cos(halfAngle));
    }

    public static Quatd rotationZ(double angleRadians) {
        double halfAngle = angleRadians * 0.5D;
        return new Quatd(0.0D, 0.0D, Math.sin(halfAngle), Math.cos(halfAngle));
    }

    public Quatd rotateX(double angleRadians) {
        return this.multiply(rotationX(angleRadians));
    }

    public Quatd rotateY(double angleRadians) {
        return this.multiply(rotationY(angleRadians));
    }

    public Quatd rotateZ(double angleRadians) {
        return this.multiply(rotationZ(angleRadians));
    }

    /** Set-equivalent composition {@code Rz(z) * Ry(y) * Rx(x)}. */
    public static Quatd rotationZYX(
            double zRadians,
            double yRadians,
            double xRadians
    ) {
        return rotationZ(zRadians)
                .multiply(rotationY(yRadians))
                .multiply(rotationX(xRadians));
    }

    /**
     * Shortest-arc rotation taking {@code from} to {@code to}.
     *
     * <p>Inputs are normalized copies. Exactly antiparallel inputs choose a
     * deterministic axis perpendicular to {@code from}. This is the explicit
     * GravityEngine contract for a case where there is no unique shortest arc.</p>
     */
    public static Quatd rotationTo(Vec3d from, Vec3d to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        if (!from.isFinite() || !to.isFinite()) {
            throw new IllegalArgumentException(
                    "rotationTo vectors must be finite");
        }

        Vec3d a = from.normalized();
        Vec3d b = to.normalized();

        double dot =
                clamp(
                        a.dot(b),
                        -1.0D,
                        1.0D
                );

        Vec3d cross = rotationCross(a, b);
        double crossLengthSquared =
                cross.lengthSquared();

        /*
         * Cross is exactly zero only when the normalized inputs are
         * numerically collinear. Parallel has the identity rotation.
         * Antiparallel has no unique shortest axis, so use the
         * deterministic GravityEngine fallback.
         */
        if (crossLengthSquared == 0.0D) {
            if (dot >= 0.0D) {
                return IDENTITY;
            }

            return fromAxisAngle(
                    perpendicularAxis(a),
                    Math.PI
            );
        }

        double crossLength =
                Math.sqrt(crossLengthSquared);

        Vec3d axis =
                cross.divide(crossLength);

        /*
         * atan2 remains well conditioned both near zero and near pi,
         * unlike treating a dot-product epsilon band as exact.
         */
        double angle =
                Math.atan2(
                        crossLength,
                        dot
                );

        return fromAxisAngle(
                axis,
                angle
        ).normalized();
    }

    /** Shortest-arc spherical interpolation. */
    public Quatd slerp(Quatd target, double t) {
        Objects.requireNonNull(target, "target");
        double cosom = this.x * target.x + this.y * target.y
                + this.z * target.z + this.w * target.w;
        double absoluteCosom = Math.abs(cosom);
        double scale0;
        double scale1;
        if (1.0D - absoluteCosom > SLERP_LINEAR_THRESHOLD) {
            double sinom = Math.sqrt(1.0D - absoluteCosom * absoluteCosom);
            double inverseSinom = 1.0D / sinom;
            double omega = Math.acos(absoluteCosom);
            scale0 = Math.sin((1.0D - t) * omega) * inverseSinom;
            scale1 = Math.sin(t * omega) * inverseSinom;
        } else {
            scale0 = 1.0D - t;
            scale1 = t;
        }
        if (cosom < 0.0D) {
            scale1 = -scale1;
        }
        return new Quatd(
                scale0 * this.x + scale1 * target.x,
                scale0 * this.y + scale1 * target.y,
                scale0 * this.z + scale1 * target.z,
                scale0 * this.w + scale1 * target.w
        );
    }

    /** Sign-invariant angular distance in radians. */
    public static double angularDistance(Quatd first, Quatd second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Quatd delta = first.normalized().conjugate()
                .multiply(second.normalized()).normalized();
        return 2.0D * Math.atan2(
                Math.sqrt(delta.x * delta.x
                        + delta.y * delta.y
                        + delta.z * delta.z),
                Math.abs(delta.w)
        );
    }

    private static Vec3d perpendicularAxis(Vec3d unit) {
        Vec3d candidate = Math.abs(unit.x()) <= Math.abs(unit.z())
                ? Vec3d.X
                : Vec3d.Z;
        Vec3d axis = unit.cross(candidate);
        if (axis.lengthSquared() <= 1.0E-24D) {
            axis = unit.cross(Vec3d.Y);
        }
        return axis.normalized();
    }

    /**
     * Cross product with compensated products.
     *
     * <p>Near antiparallel inputs make the ordinary cross product's
     * difference-of-products cancellation lose the small residual direction.
     * Error-free product and sum terms recover that direction without
     * treating the angular difference as zero.</p>
     */
    private static Vec3d rotationCross(Vec3d a, Vec3d b) {
        return new Vec3d(
                crossComponent(
                        a.y(), a.z(),
                        b.y(), b.z()
                ),
                crossComponent(
                        a.z(), a.x(),
                        b.z(), b.x()
                ),
                crossComponent(
                        a.x(), a.y(),
                        b.x(), b.y()
                )
        );
    }

    private static double crossComponent(
            double leftFirst,
            double leftSecond,
            double rightFirst,
            double rightSecond
    ) {
        double first = leftFirst * rightSecond;
        double firstError =
                Math.fma(leftFirst, rightSecond, -first);
        double second = leftSecond * rightFirst;
        double secondError =
                Math.fma(leftSecond, rightFirst, -second);
        double difference = first - second;
        double differenceError =
                (first - difference) - second;
        return difference
                + (differenceError
                + firstError
                - secondError);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
