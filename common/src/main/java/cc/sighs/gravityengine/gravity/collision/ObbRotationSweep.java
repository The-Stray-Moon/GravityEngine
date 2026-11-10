package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Conservative, bounded rigid angular sweep through the shortest quaternion arc.
 * Locomotion is separate; pivot-induced center motion belongs to this rigid trajectory. Every accepted angular interval is
 * covered by an OBB enclosing the ENTIRE interval, not merely sampled endpoints.
 * The oracle must use exact narrow phase and fail closed on query/work-budget failure.
 * It may test clearance or legality relative to the authoritative starting body.
 * Both exact bodies and conservative interval envelopes pass through that same
 * predicate; accepting only sampled endpoints would lose swept coverage.
 */
public final class ObbRotationSweep {
    private static final int MAX_DEPTH = 18;
    private static final int MAX_QUERIES = 256;
    private ObbRotationSweep() {}

    public record Result(OrientedBox body, double fraction, boolean clipped, boolean indeterminate) {
        public Result(OrientedBox body, double fraction, boolean clipped) { this(body, fraction, clipped, false); }
    }

    public static Result resolve(OrientedBox start, Quatd desired,
                                 Predicate<OrientedBox> legal) {
        return resolve(new ObbAngularTrajectory(start, desired, null), legal);
    }

    public static Result resolve(ObbAngularTrajectory trajectory, Predicate<OrientedBox> legal) {
        return resolveTimed(trajectory, (body, t) -> legal.test(body), (first, second, lo, hi) -> legal.test(first));
    }

    public static Result resolve(ObbAngularTrajectory trajectory, BodyCollisionDelta.Baseline baseline) {
        return resolveTimed(trajectory, (body, t) -> baseline.compare(body).legal(),
                (first, second, lo, hi) -> baseline.enclosesLegalInterval(first, second));
    }

    public static Result resolve(ObbAngularTrajectory trajectory, BodyCollisionDelta.Baseline baseline,
            SweepTimeWindow window) {
        return resolveTimed(trajectory,
                (body, t) -> baseline.compareAt(body, window.startTicks() + t * window.durationTicks()).legal(),
                (first, second, lo, hi) -> baseline.enclosesLegalIntervalAt(first, second, window.segment(lo, hi)));
    }

    @FunctionalInterface private interface InstantLegality { boolean test(OrientedBox body, double time); }
    @FunctionalInterface private interface IntervalLegality {
        boolean test(OrientedBox first, OrientedBox second, double lo, double hi);
    }
    private static Result resolveTimed(ObbAngularTrajectory trajectory, InstantLegality legal,
            IntervalLegality intervalLegal) {
        Objects.requireNonNull(trajectory, "trajectory");
        Objects.requireNonNull(legal, "legal");
        OrientedBox start = trajectory.start();
        Budget budget = new Budget();
        if (!test(start, 0, legal, budget)) return new Result(start, 0, true);
        double fraction = advance(trajectory, 0, 1, 0, intervalLegal, budget);
        if (budget.exhausted) return new Result(start, 0, true, true);
        OrientedBox accepted = trajectory.bodyAt(fraction);
        // Never replace a failed final query with the requested endpoint.
        if (!test(accepted, fraction, legal, budget)) return new Result(start, 0, true);
        return new Result(accepted, fraction, fraction < 1);
    }

    private static double advance(ObbAngularTrajectory trajectory,
                                  double lo, double hi, int depth,
                                  IntervalLegality legal, Budget budget) {
        if (budget.queries >= MAX_QUERIES - 1) { budget.exhausted = true; return lo; }
        OrientedBox envelope = trajectory.envelope(lo, hi);
        OrientedBox supportEnvelope = trajectory.pivot() == null ? envelope
                : envelope(trajectory, lo, hi, BodyOrientation3d.quaternion(trajectory.pivot().supportFrame()));
        budget.queries += trajectory.pivot() == null ? 1 : 2;
        if (budget.queries >= MAX_QUERIES) { budget.exhausted = true; return lo; }
        if (legal.test(envelope, supportEnvelope, lo, hi)) return hi;
        if (budget.queries >= MAX_QUERIES - 1) { budget.exhausted = true; return lo; }
        if (depth >= MAX_DEPTH) return lo;
        double mid = (lo + hi) * 0.5;
        double first = advance(trajectory, lo, mid, depth + 1, legal, budget);
        if (first < mid) return first;
        return advance(trajectory, mid, hi, depth + 1, legal, budget);
    }

    private static boolean test(OrientedBox body, double time, InstantLegality legal, Budget budget) {
        if (budget.queries++ >= MAX_QUERIES) return false;
        return legal.test(body, time);
    }

    /**
     * Conservative fixed-center radius containing every corner of every
     * temporary OBB that {@link #envelope} may construct for this body.
     *
     * <p>An exact rotated corner always remains at distance
     * {@code |halfExtents|} from the center.  The interval envelope computes
     * each local half-axis independently, so each envelope half-axis is at most
     * that exact corner radius plus the same outward-rounding allowance used by
     * {@link #envelope}.  A corner of the resulting temporary OBB can combine
     * all three independent maxima, hence the {@code sqrt(3)} factor.</p>
     *
     * <p>This bounds solver query geometry, not committed actor geometry.</p>
     */
    public static double maximumEnvelopeCornerRadius(OrientedBox start) {
        Objects.requireNonNull(start, "start");

        Vec3d h = start.halfExtents();
        double exactCornerRadius = h.length();

        double roundoff =
                64.0D * Math.ulp(Math.max(1.0D, exactCornerRadius));

        double maximumEnvelopeHalfAxis =
                Math.nextUp(exactCornerRadius + roundoff);

        return Math.sqrt(3.0D) * maximumEnvelopeHalfAxis;
    }

    /** Public mathematical seam: a guaranteed bound for every corner at every t in [lo,hi]. */
    public static OrientedBox envelope(OrientedBox start, Quatd fromValue,
                                      Quatd toValue, double lo, double hi) {
        return envelope(new ObbAngularTrajectory(new OrientedBox(start.center(), start.halfExtents(),
                BodyOrientation3d.frame(fromValue)), toValue, null), lo, hi);
    }

    public static OrientedBox envelope(ObbAngularTrajectory trajectory, double lo, double hi) {
        return envelope(trajectory, lo, hi, null);
    }

    /** A second conservative bound in the real support-face basis avoids
     * midpoint-box corners cutting through a plane tangent to the rigid path.
     * Each bound encloses the whole interval independently. */
    public static OrientedBox envelope(ObbAngularTrajectory trajectory, double lo, double hi,
            Quatd envelopeOrientation) {
        if (!(0 <= lo && lo <= hi && hi <= 1)) throw new IllegalArgumentException("interval");
        OrientedBox start = trajectory.start();
        double sinHalf = trajectory.relativeSineHalf();
        double angle = trajectory.relativeAngle();
        if (sinHalf < 1e-15 || hi == lo) return trajectory.bodyAt((lo + hi) * 0.5);
        Quatd mid = trajectory.rotationAt((lo + hi) * 0.5);
        Quatd enclosureQ = envelopeOrientation == null ? mid : BodyOrientation3d.normalized(envelopeOrientation);
        Quatd enclosureFromMid = enclosureQ.conjugate().multiply(mid);
        Vec3d axis = trajectory.relativeAxis();
        double halfAngle = angle * (hi - lo) * 0.5;
        double cosineHalfAngle = Math.cos(halfAngle);
        double sineHalfAngle = Math.sin(halfAngle);
        Vec3d h = start.halfExtents();
        Vec3d anchor = trajectory.pivot() == null ? Vec3d.ZERO : trajectory.pivot().body();
        double lowerX = Double.POSITIVE_INFINITY;
        double lowerY = Double.POSITIVE_INFINITY;
        double lowerZ = Double.POSITIVE_INFINITY;
        double upperX = Double.NEGATIVE_INFINITY;
        double upperY = Double.NEGATIVE_INFINITY;
        double upperZ = Double.NEGATIVE_INFINITY;
        // q(mid)^-1 q(t) rotates about this constant BODY-local axis.
        for (int bits = 0; bits < 8; bits++) {
            Vec3d v = new Vec3d((bits & 1) == 0 ? -h.x() : h.x(),
                    (bits & 2) == 0 ? -h.y() : h.y(), (bits & 4) == 0 ? -h.z() : h.z());
            // In the midpoint basis every corner follows R(theta) * (v - a).
            // Extrema include the center arc, not just corner motion about C.
            v = v.subtract(anchor);
            double dot = axis.dot(v);
            Vec3d cross = axis.cross(v);
            Vec3d constant = axis.multiply(dot);
            Vec3d cosine = v.subtract(constant);
            constant = enclosureFromMid.transform(constant);
            cosine = enclosureFromMid.transform(cosine);
            cross = enclosureFromMid.transform(cross);
            for (int i = 0; i < 3; i++) {
                double c = constant.component(i);
                double a = cosine.component(i);
                double b = cross.component(i);
                double atNegative = a * cosineHalfAngle
                        + b * (-sineHalfAngle) + c;
                double atPositive = a * cosineHalfAngle
                        + b * sineHalfAngle + c;
                double minimum = Math.min(atNegative, atPositive);
                double maximum = Math.max(atNegative, atPositive);
                double root = Math.atan2(b, a);
                for (int k = -2; k <= 2; k++) {
                    double theta = root + k * Math.PI;
                    if (theta >= -halfAngle && theta <= halfAngle) {
                        minimum = Math.min(minimum, value(a,b,c,theta));
                        maximum = Math.max(maximum, value(a,b,c,theta));
                    }
                }
                if (i == 0) {
                    lowerX = Math.min(lowerX, minimum);
                    upperX = Math.max(upperX, maximum);
                } else if (i == 1) {
                    lowerY = Math.min(lowerY, minimum);
                    upperY = Math.max(upperY, maximum);
                } else {
                    lowerZ = Math.min(lowerZ, minimum);
                    upperZ = Math.max(upperZ, maximum);
                }
            }
        }
        // Round outward. This is a query enclosure, never the committed exact collider.
        double roundoff =
                64.0D * Math.ulp(Math.max(1.0D, h.length() + anchor.length()));
        double boundX = (upperX - lowerX) * 0.5D;
        double boundY = (upperY - lowerY) * 0.5D;
        double boundZ = (upperZ - lowerZ) * 0.5D;
        Vec3d bound = new Vec3d(
                Math.nextUp(boundX + roundoff),
                Math.nextUp(boundY + roundoff),
                Math.nextUp(boundZ + roundoff)
        );
        Vec3d center = trajectory.pivot() == null ? start.center() : trajectory.pivot().world();
        Vec3d lowerUpperMidpoint = new Vec3d(
                (lowerX + upperX) * 0.5D,
                (lowerY + upperY) * 0.5D,
                (lowerZ + upperZ) * 0.5D
        );
        center = center.add(
                enclosureQ.transform(lowerUpperMidpoint));
        return new OrientedBox(center, bound, BodyOrientation3d.frame(enclosureQ));
    }

    private static double value(double a, double b, double c, double theta) {
        return a * Math.cos(theta) + b * Math.sin(theta) + c;
    }
    private static final class Budget { int queries; boolean exhausted; }
}
