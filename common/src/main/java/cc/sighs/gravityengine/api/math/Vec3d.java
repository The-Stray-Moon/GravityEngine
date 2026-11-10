package cc.sighs.gravityengine.api.math;

/**
 * Immutable three-component double vector.
 *
 * <p>This is GravityEngine's supported mathematical value type. It is a plain
 * value with exact component equality: {@link #equals(Object)} is not a
 * geometric tolerance and algorithms that need a tolerance must express that
 * policy in their owning kernel.</p>
 *
 * <p>Construction accepts non-finite components so kernels can inspect and
 * reject numerical failure explicitly. Public API values that require finite
 * state validate with {@link #isFinite()} at their own boundary.</p>
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);
    public static final Vec3d X = new Vec3d(1.0D, 0.0D, 0.0D);
    public static final Vec3d Y = new Vec3d(0.0D, 1.0D, 0.0D);
    public static final Vec3d Z = new Vec3d(0.0D, 0.0D, 1.0D);

    public Vec3d(Vec3d value) {
        this(value.x, value.y, value.z);
    }

    public boolean isFinite() {
        return Double.isFinite(this.x)
                && Double.isFinite(this.y)
                && Double.isFinite(this.z);
    }

    public double component(int index) {
        return switch (index) {
            case 0 -> this.x;
            case 1 -> this.y;
            case 2 -> this.z;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(
                this.x + other.x,
                this.y + other.y,
                this.z + other.z
        );
    }

    public Vec3d add(double x, double y, double z) {
        return new Vec3d(this.x + x, this.y + y, this.z + z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(
                this.x - other.x,
                this.y - other.y,
                this.z - other.z
        );
    }

    public Vec3d subtract(double x, double y, double z) {
        return new Vec3d(this.x - x, this.y - y, this.z - z);
    }

    public Vec3d negate() {
        return new Vec3d(-this.x, -this.y, -this.z);
    }

    public Vec3d multiply(double scalar) {
        return new Vec3d(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    public Vec3d divide(double divisor) {
        return new Vec3d(
                this.x / divisor,
                this.y / divisor,
                this.z / divisor
        );
    }

    public Vec3d multiply(double x, double y, double z) {
        return new Vec3d(this.x * x, this.y * y, this.z * z);
    }

    /** Returns {@code this + scalar * addend}. */
    public Vec3d fma(double scalar, Vec3d addend) {
        return new Vec3d(
                this.x + scalar * addend.x,
                this.y + scalar * addend.y,
                this.z + scalar * addend.z
        );
    }

    public double dot(Vec3d other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public Vec3d cross(Vec3d other) {
        return new Vec3d(
                this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z,
                this.x * other.y - this.y * other.x
        );
    }

    public double lengthSquared() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double distanceSquared(Vec3d other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3d other) {
        return Math.sqrt(distanceSquared(other));
    }

    /**
     * Unit-length copy.
     *
     * <p>Non-finite vectors and vectors whose squared length is exactly zero
     * (including a finite vector that underflows) are rejected explicitly.
     * This deliberately does not copy any Minecraft or general-purpose
     * epsilon policy into vector semantics.</p>
     */
    public Vec3d normalized() {
        if (!isFinite()) {
            throw new IllegalArgumentException("vector must be finite: " + this);
        }
        double lengthSquared = lengthSquared();
        if (!Double.isFinite(lengthSquared) || lengthSquared == 0.0D) {
            throw new IllegalArgumentException(
                    "vector must be non-degenerate: " + this);
        }
        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        return new Vec3d(
                this.x * inverseLength,
                this.y * inverseLength,
                this.z * inverseLength
        );
    }

    public Vec3d lerp(Vec3d other, double t) {
        return new Vec3d(
                this.x + (other.x - this.x) * t,
                this.y + (other.y - this.y) * t,
                this.z + (other.z - this.z) * t
        );
    }

    public Vec3d abs() {
        return new Vec3d(Math.abs(this.x), Math.abs(this.y), Math.abs(this.z));
    }

    public Vec3d componentMin(Vec3d other) {
        return new Vec3d(
                Math.min(this.x, other.x),
                Math.min(this.y, other.y),
                Math.min(this.z, other.z)
        );
    }

    public Vec3d componentMax(Vec3d other) {
        return new Vec3d(
                Math.max(this.x, other.x),
                Math.max(this.y, other.y),
                Math.max(this.z, other.z)
        );
    }

    public Vec3d withComponent(int index, double value) {
        return switch (index) {
            case 0 -> new Vec3d(value, this.y, this.z);
            case 1 -> new Vec3d(this.x, value, this.z);
            case 2 -> new Vec3d(this.x, this.y, value);
            default -> throw new IndexOutOfBoundsException(index);
        };
    }
}
