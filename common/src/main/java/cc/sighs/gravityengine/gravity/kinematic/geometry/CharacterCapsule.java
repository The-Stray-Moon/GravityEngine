package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * A segment Minkowski-summed with a sphere. Yaw is deliberately absent.
 *
 * <p>Neutral kernel geometry: all coordinates are JOML vectors and the
 * enclosing bounds are {@link Aabb3d}.  Minecraft conversion happens in the
 * adapter layer.</p>
 */
public record CharacterCapsule(
        Vector3d center,
        Vector3d axis,
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
        center = new Vector3d(center);
        axis = new Vector3d(axis);
        if (Math.abs(axis.lengthSquared() - 1) > 4 * Math.ulp(1.0)) axis.normalize();
    }

    public static CharacterCapsule fromDimensions(
            Vector3dc center,
            double width,
            double height,
            OrthonormalFrame3d frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return fromDimensions(center, width, height, frame.axisY(new Vector3d()));
    }

    public static CharacterCapsule fromDimensions(Vector3dc center, double width, double height, Vector3dc up) {
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
                new Vector3d(center),
                new Vector3d(up),
                radius,
                halfSegmentLength);
    }

    /** Fresh defensive center copy. */
    @Override
    public Vector3d center() {
        return new Vector3d(center);
    }

    /** Fresh defensive normalized axis copy. */
    public Vector3d axis() {
        return new Vector3d(axis);
    }

    public Vector3d a() {
        return new Vector3d(center).sub(
                axis.x * halfSegmentLength,
                axis.y * halfSegmentLength,
                axis.z * halfSegmentLength);
    }

    public Vector3d b() {
        return new Vector3d(center).add(
                axis.x * halfSegmentLength,
                axis.y * halfSegmentLength,
                axis.z * halfSegmentLength);
    }

    @Override
    public CharacterCapsule move(Vector3dc displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new CharacterCapsule(
                new Vector3d(center).add(displacement), axis, radius, halfSegmentLength
        );
    }

    public CharacterCapsule withCenter(Vector3dc newCenter) {
        return new CharacterCapsule(
                new Vector3d(newCenter), axis, radius, halfSegmentLength);
    }

    @Override
    public Aabb3d enclosingAabb() {
        Vector3d first = a();
        Vector3d second = b();
        return new Aabb3d(
                Math.min(first.x, second.x) - radius,
                Math.min(first.y, second.y) - radius,
                Math.min(first.z, second.z) - radius,
                Math.max(first.x, second.x) + radius,
                Math.max(first.y, second.y) + radius,
                Math.max(first.z, second.z) + radius
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

    public Vector3d bottomPoint() { return center().fma(-halfSegmentLength - radius, axis); }

    /** Also valid for non-unit query directions. */
    public double projectionRadius(Vector3dc direction) {
        return radius * direction.length() + halfSegmentLength * Math.abs(direction.dot(axis));
    }

    @Override public boolean geometricallyEquals(CollisionBody other) {
        return other instanceof CharacterCapsule c && center.equals(c.center)
                && radius == c.radius && halfSegmentLength == c.halfSegmentLength
                && (halfSegmentLength == 0 || axis.equals(c.axis) || axis.equals(c.axis().negate()));
    }

    /** Analytic segment/solid interval: union of the axial barrel and endpoint balls.
     * The union is convex; its nonempty intervals join without gaps. */
    public double[] segmentInterval(Vector3dc start, Vector3dc end) {
        Vector3d p = new Vector3d(start).sub(center), d = new Vector3d(end).sub(start);
        double z = p.dot(axis), dz = d.dot(axis);
        double[] result = null;
        for (double h : new double[]{-halfSegmentLength, halfSegmentLength}) {
            Vector3d q = new Vector3d(p).fma(-h, axis);
            result = union(result, quadraticInterval(d.lengthSquared(), q.dot(d), q.lengthSquared()-radius*radius, 0, 1));
        }
        double lo = 0, hi = 1;
        if (dz == 0) {
            if (Math.abs(z) > halfSegmentLength) return result;
        } else {
            double t0 = (-halfSegmentLength-z)/dz, t1 = (halfSegmentLength-z)/dz;
            lo = Math.max(lo, Math.min(t0,t1)); hi = Math.min(hi, Math.max(t0,t1));
        }
        p.fma(-z,axis); d.fma(-dz,axis);
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

    private static void requireFinite(Vector3d vector, String name) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(name + " must be finite: " + vector);
        }
    }
}
