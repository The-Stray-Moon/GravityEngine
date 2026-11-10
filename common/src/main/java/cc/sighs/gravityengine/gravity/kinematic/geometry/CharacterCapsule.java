package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import java.util.Objects;

/**
 * A segment Minkowski-summed with a sphere. Yaw is deliberately absent.
 *
 * <p>Neutral kernel geometry: all coordinates are GravityEngine {@link Vec3d}
 * values and the enclosing bounds are {@link Aabb3d}. Minecraft conversion
 * happens in the adapter layer.</p>
 */
public record CharacterCapsule(
        Vec3d center,
        Vec3d axis,
        double radius,
        double halfSegmentLength
) implements CollisionBody {
    private static final double AXIS_LENGTH_EPSILON = 1.0E-12D;

    public CharacterCapsule {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(axis, "axis");
        requireFinite(center, "center");
        requireFinite(axis, "axis");
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException("radius must be finite and non-negative: " + radius);
        }
        if (!Double.isFinite(halfSegmentLength) || halfSegmentLength < 0.0D) {
            throw new IllegalArgumentException(
                    "halfSegmentLength must be finite and non-negative: " + halfSegmentLength
            );
        }
        double axisLengthSquared = axis.lengthSquared();
        if (!Double.isFinite(axisLengthSquared) || axisLengthSquared <= AXIS_LENGTH_EPSILON) {
            throw new IllegalArgumentException("axis must be non-zero: " + axis);
        }
        if (Math.abs(axis.lengthSquared() - 1.0D) > 4 * Math.ulp(1.0D)) {
            axis = axis.normalized();
        }
    }

    public static CharacterCapsule fromDimensions(
            Vec3d center,
            double width,
            double height,
            OrthonormalFrame3d frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return fromDimensions(center, width, height, frame.axisY());
    }

    public static CharacterCapsule fromDimensions(
            Vec3d center,
            double width,
            double height,
            Vec3d up
    ) {
        if (!Double.isFinite(width) || !Double.isFinite(height)
                || width < 0.0D || height < 0.0D) {
            throw new IllegalArgumentException(
                    "capsule dimensions must be finite and non-negative: width="
                            + width + ", height=" + height
            );
        }
        if (height < width) {
            throw new IllegalArgumentException(
                    "vertical capsule cannot preserve height < width: width="
                            + width + ", height=" + height
            );
        }
        double radius = width * 0.5D;
        double halfSegmentLength = Math.max(0.0D, height * 0.5D - radius);
        return new CharacterCapsule(
                center,
                up,
                radius,
                halfSegmentLength);
    }

    public Vec3d a() {
        return center.subtract(
                axis.x() * halfSegmentLength,
                axis.y() * halfSegmentLength,
                axis.z() * halfSegmentLength);
    }

    public Vec3d b() {
        return center.add(
                axis.x() * halfSegmentLength,
                axis.y() * halfSegmentLength,
                axis.z() * halfSegmentLength);
    }

    @Override
    public CharacterCapsule move(Vec3d displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new CharacterCapsule(
                center.add(displacement), axis, radius, halfSegmentLength
        );
    }

    public CharacterCapsule withCenter(Vec3d newCenter) {
        return new CharacterCapsule(
                newCenter, axis, radius, halfSegmentLength);
    }

    @Override
    public Aabb3d enclosingAabb() {
        Vec3d first = a();
        Vec3d second = b();
        return new Aabb3d(
                Math.min(first.x(), second.x()) - radius,
                Math.min(first.y(), second.y()) - radius,
                Math.min(first.z(), second.z()) - radius,
                Math.max(first.x(), second.x()) + radius,
                Math.max(first.y(), second.y()) + radius,
                Math.max(first.z(), second.z()) + radius
        );
    }

    public double totalHeight() {
        return halfSegmentLength * 2.0D + radius * 2.0D;
    }

    /** Minkowski expansion by a sphere, preserving the spine. */
    public CharacterCapsule inflated(double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("invalid inflation");
        return new CharacterCapsule(center, axis, radius + amount, halfSegmentLength);
    }

    public Vec3d bottomPoint() {
        return center().fma(-halfSegmentLength - radius, axis);
    }

    /** Also valid for non-unit query directions. */
    public double projectionRadius(Vec3d direction) {
        return radius * direction.length() + halfSegmentLength * Math.abs(direction.dot(axis));
    }

    @Override public boolean geometricallyEquals(CollisionBody other) {
        return other instanceof CharacterCapsule c && center.equals(c.center)
                && radius == c.radius && halfSegmentLength == c.halfSegmentLength
                && (halfSegmentLength == 0 || axis.equals(c.axis) || axis.equals(c.axis().negate()));
    }

    /** Analytic segment/solid interval: union of the axial barrel and endpoint balls.
     * The union is convex; its nonempty intervals join without gaps. */
    public double[] segmentInterval(Vec3d start, Vec3d end) {
        Vec3d p = start.subtract(center);
        Vec3d d = end.subtract(start);
        double z = p.dot(axis), dz = d.dot(axis);
        double[] result = null;
        for (double h : new double[]{-halfSegmentLength, halfSegmentLength}) {
            Vec3d q = p.fma(-h, axis);
            result = union(result, quadraticInterval(d.lengthSquared(), q.dot(d), q.lengthSquared()-radius*radius, 0, 1));
        }
        double lo = 0, hi = 1;
        if (dz == 0) {
            if (Math.abs(z) > halfSegmentLength) return result;
        } else {
            double t0 = (-halfSegmentLength-z)/dz, t1 = (halfSegmentLength-z)/dz;
            lo = Math.max(lo, Math.min(t0,t1)); hi = Math.min(hi, Math.max(t0,t1));
        }
        p = p.fma(-z, axis);
        d = d.fma(-dz, axis);
        return union(result, quadraticInterval(d.lengthSquared(), p.dot(d), p.lengthSquared()-radius*radius, lo, hi));
    }
    private static double[] quadraticInterval(double a, double b, double c, double lo, double hi) {
        if (lo > hi) return null;
        if (a == 0) return c <= 0 ? new double[]{lo,hi} : null;
        double discriminant = b*b-a*c;
        if (discriminant < 0) return null;
        double root = Math.sqrt(discriminant);
        // Stable quadratic roots, avoiding cancellation on near-surface rays.
        double q = -b-Math.copySign(root,b);
        double t0 = q == 0 ? -b/a : q/a, t1 = q == 0 ? t0 : c/q;
        lo = Math.max(lo,Math.min(t0,t1)); hi = Math.min(hi,Math.max(t0,t1));
        return lo <= hi ? new double[]{lo,hi} : null;
    }
    private static double[] union(double[] a, double[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new double[]{Math.min(a[0],b[0]),Math.max(a[1],b[1])};
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(name + " must be finite: " + vector);
        }
    }
}
