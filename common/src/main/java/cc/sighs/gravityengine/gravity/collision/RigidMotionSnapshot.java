package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Immutable rigid publication with constant twist or shortest-arc normalized-linear rotation. No retained mutable vectors or producer callbacks.
 * Times are normalized fractions of intervalTicks from the operation origin. Linear
 * displacement is blocks/interval; angular displacement is a WORLD rotation vector in
 * radians/interval (not a shortest-arc endpoint quaternion). Point velocity is blocks/TICK.
 * Source/body identity is paired with this publication by DynamicCollisionObstacleSnapshot.
 * Revision identifies the publication; continuityEpoch changes on replacement/discontinuity. */
public record RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, double dx, double dy, double dz,
                                  double ax, double ay, double az, long tick, long revision, long continuityEpoch,
                                  double intervalTicks, boolean normalizedLinearRotation) implements RigidTrajectory {
    public RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start,
            double dx, double dy, double dz, double ax, double ay, double az,
            long tick, long revision, long continuityEpoch, double intervalTicks) {
        this(start, dx, dy, dz, ax, ay, az, tick, revision, continuityEpoch, intervalTicks, false);
    }

    public RigidMotionSnapshot {
        Objects.requireNonNull(start, "start");
        if (!new Vec3d(dx, dy, dz).isFinite() || !new Vec3d(ax, ay, az).isFinite()
                || !Double.isFinite(new Vec3d(dx, dy, dz).length())
                || !Double.isFinite(new Vec3d(ax, ay, az).length())
                || !Double.isFinite(intervalTicks) || intervalTicks <= 0 || revision < 0 || continuityEpoch < 0
                || !new Vec3d(dx, dy, dz).divide(intervalTicks).isFinite()
                || !new Vec3d(ax, ay, az).divide(intervalTicks).isFinite())
            throw new IllegalArgumentException("invalid rigid publication");
        if (normalizedLinearRotation && new Vec3d(ax, ay, az).length() > Math.PI + 1e-12)
            throw new IllegalArgumentException("normalized linear rotation requires a shortest arc");
    }
    public RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, Vec3d displacement, Vec3d angularDisplacement,
                               long tick, long revision, long continuityEpoch, double intervalTicks) {
        this(start, displacement.x(), displacement.y(), displacement.z(), angularDisplacement.x(),
                angularDisplacement.y(), angularDisplacement.z(), tick, revision, continuityEpoch, intervalTicks);
    }
    public Vec3d displacementForInterval() { return new Vec3d(dx, dy, dz); }
    @Override public RigidMotionSnapshot invariantAxisMotion() { return this; }
    public boolean requiresGeneralSweep() { return ax != 0 || ay != 0 || az != 0; }
    public boolean moving() { return requiresGeneralSweep() || dx != 0 || dy != 0 || dz != 0; }
    public cc.sighs.gravityengine.math.geometry.RigidPose poseAt(double t) {
        requireTime(t);
        if (t == 0) return start;
        Quatd q = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(start.orientation());
        if (requiresGeneralSweep()) {
            double angle = new Vec3d(ax, ay, az).length();
            Quatd delta = Quatd.fromAxisAngle(
                    new Vec3d(ax / angle, ay / angle, az / angle),
                    rotationAngleAt(t)
            );
            q = delta.multiply(q);
        }
        return new cc.sighs.gravityengine.math.geometry.RigidPose(start.center().fma(t, displacementForInterval()),
                requiresGeneralSweep() ? cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(q) : start.orientation());
    }
    public Vec3d velocityAt(Vec3d worldPoint, double t) {
        Vec3d radius = worldPoint.subtract(poseAt(t).center());
        return new Vec3d(ax, ay, az).multiply(angularRateFactor(t)).cross(radius).add(displacementForInterval()).divide(intervalTicks);
    }
    public OrientedBox bodyAt(OrientedBox local, double t) {
        cc.sighs.gravityengine.math.geometry.RigidPose pose = poseAt(t);
        var orientation = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(pose.orientation())
                .multiply(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(local.orientation()));
        return new OrientedBox(pose.transformPoint(local.center()), local.halfExtents(),
                start.orientation().equals(cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY) && !requiresGeneralSweep()
                        ? local.orientation() : cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(orientation));
    }
    /** Exact extrema enclosure for arcs <= pi, spherical enclosure for longer arcs.
     * Refinement narrows the angular interval; no fixed motion substeps are used. */
    public OrientedBox envelope(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi, Vec3d.ZERO);
    }
    /** Proof enclosure in a translating observer's coordinates. Subtracting the
     * observer's interval displacement preserves relative-time correlation at
     * initially touching/outward contacts. It does not change the publication. */
    public OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observerDisplacement) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        OrientedBox first = bodyAt(local, lo);
        if (hi == lo) return first;
        Vec3d translation = displacementForInterval().multiply(hi - lo).subtract(observerDisplacement);
        OrientedBox rotation = first;
        double angle = normalizedLinearRotation
                ? rotationAngleAt(hi) - rotationAngleAt(lo)
                : new Vec3d(ax, ay, az).length() * (hi - lo);
        if (angle > Math.PI) {
            double radius = local.center().length() + local.halfExtents().length();
            rotation = new OrientedBox(poseAt(lo).center(), radius, cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        } else if (requiresGeneralSweep()) {
            Vec3d axis = new Vec3d(ax, ay, az).normalized();
            Quatd target = Quatd.fromAxisAngle(axis, angle)
                    .multiply(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(first.orientation()));
            var pivot = ObbAngularTrajectory.Pivot.at(first, poseAt(lo).center(), first.orientation());
            rotation = new ObbAngularTrajectory(first, target, pivot).envelope(0, 1);
        }
        Vec3d halfTranslation = rotation
                .worldVectorToLocal(translation)
                .abs()
                .multiply(.5);
        var independent = new OrientedBox(rotation.center().fma(.5, translation),
                rotation.halfExtents().add(halfTranslation), rotation.orientation());
        if (!requiresGeneralSweep() || observerDisplacement.lengthSquared() == 0) return independent;
        // Rotation and relative translation can cancel along a contact normal.
        // Their independent extrema lose that cancellation. Bound each material
        // corner's relative chord instead: linear interpolation error is <=
        // max|p''| * dt^2 / 8. Rotation about a fixed axis contributes no
        // acceleration along that axis, including the nlerp speed profile.
        var last = bodyAt(local, hi).move(observerDisplacement.negate());
        Vec3d min = new Vec3d(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3d max = min.negate();
        var h = local.halfExtents();
        for (int bits=0; bits<8; bits++) {
            var corner = new Vec3d((bits&1)==0?-h.x():h.x(), (bits&2)==0?-h.y():h.y(), (bits&4)==0?-h.z():h.z());
            var a = corner;
            var b = first.worldPointToLocal(last.localPointToWorld(corner));
            min = new Vec3d(Math.min(min.x(),Math.min(a.x(),b.x())), Math.min(min.y(),Math.min(a.y(),b.y())), Math.min(min.z(),Math.min(a.z(),b.z())));
            max = new Vec3d(Math.max(max.x(),Math.max(a.x(),b.x())), Math.max(max.y(),Math.max(a.y(),b.y())), Math.max(max.z(),Math.max(a.z(),b.z())));
        }
        var axis = new Vec3d(ax,ay,az).normalized();
        double error = maximumPointSecondDerivativeU(local)*(hi-lo)*(hi-lo)/8;
        var padding = new Vec3d(first.orientation().axisX().cross(axis).length(),
                first.orientation().axisY().cross(axis).length(), first.orientation().axisZ().cross(axis).length()).multiply(error);
        var chord = new OrientedBox(first.localPointToWorld(min.add(max).multiply(.5)),
                max.subtract(min).multiply(.5).add(padding), first.orientation());
        double chordSize = chord.halfExtents().lengthSquared();
        return chordSize < independent.halfExtents().lengthSquared() ? chord : independent;
    }
    public cc.sighs.gravityengine.math.geometry.Aabb3d sweptBounds(OrientedBox local, double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        if (!requiresGeneralSweep()) return bodyAt(local, lo).enclosingAabb()
                .expandTowards(displacementForInterval().multiply(hi - lo));
        return envelope(local, lo, hi).enclosingAabb();
    }
    /** Center translation over an explicit normalized interval. Keeps the
     * translation fast path's multiplication order without subtracting two large world positions. */
    public Vec3d centerDisplacement(double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        return displacementForInterval().multiply(hi - lo);
    }
    /** Conservative maximum displacement of ANY material point from its initial position. */
    public double maximumPointDisplacement(OrientedBox local) {
        double angle = new Vec3d(ax, ay, az).length();
        return displacementForInterval().length() + (local.center().length() + local.halfExtents().length())
                * (angle >= Math.PI ? 2 : 2 * Math.sin(angle * .5));
    }
    /** Conservative material-point speed in blocks/tick, used to bound CCD brackets. */
    public double maximumPointSpeed(OrientedBox local) {
        return (displacementForInterval().length() + maximumAngularDerivativeU()
                * (local.center().length() + local.halfExtents().length())) / intervalTicks;
    }
    /** Angle along the published interpolation profile at normalized time. */
    public double rotationAngleAt(double t) {
        requireTime(t);
        double angle = new Vec3d(ax, ay, az).length();
        if (!normalizedLinearRotation || angle == 0) return angle * t;
        return 2 * Math.atan2(t * Math.sin(angle * .5), 1 - t + t * Math.cos(angle * .5));
    }

    private double angularRateFactor(double t) {
        if (!normalizedLinearRotation) return 1;
        double angle = new Vec3d(ax, ay, az).length();
        if (angle == 0) return 1;
        double sin = Math.sin(angle * .5);
        double x = 1 - t + t * Math.cos(angle * .5);
        double y = t * sin;
        return 2 * sin / (angle * (x * x + y * y));
    }

    /** Conservative angular rate in radians per normalized interval. */
    public double maximumAngularDerivativeU() {
        double angle = new Vec3d(ax, ay, az).length();
        return normalizedLinearRotation ? 4 * Math.tan(angle * .25) : angle;
    }

    @Override public double maximumPointSecondDerivativeU(OrientedBox local) {
        return maximumUnitDirectionAcceleration()
                * (local.center().length() + local.halfExtents().length());
    }
    /** Bound on the second derivative of a rotating unit direction. */
    double maximumUnitDirectionAcceleration() {
        double rate = maximumAngularDerivativeU();
        double angularAcceleration = 0;
        if (normalizedLinearRotation) {
            double angle = new Vec3d(ax, ay, az).length();
            double cosine = Math.cos(angle * .5);
            double minimumDenominator = (1 + cosine) * .5;
            angularAcceleration = 4 * Math.sin(angle * .5) * (1 - cosine)
                    / (minimumDenominator * minimumDenominator);
        }
        return rate * rate + angularAcceleration;
    }

    private static void requireTime(double t) {
        if (!Double.isFinite(t) || t < 0 || t > 1)
            throw new CollisionSceneCoverageException("rigid publication does not cover time " + t);
    }
}
