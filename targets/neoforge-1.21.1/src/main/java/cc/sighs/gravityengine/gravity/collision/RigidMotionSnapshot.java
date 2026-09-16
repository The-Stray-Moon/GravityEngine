package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.Objects;

/** Completed constant-twist publication. No retained mutable vectors or producer callbacks.
 * Times are normalized fractions of intervalTicks from the operation origin. Linear
 * displacement is blocks/interval; angular displacement is a WORLD rotation vector in
 * radians/interval (not a shortest-arc endpoint quaternion). Point velocity is blocks/TICK.
 * Source/body identity is paired with this publication by DynamicCollisionObstacleSnapshot.
 * Revision identifies the publication; continuityEpoch changes on replacement/discontinuity. */
public record RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, double dx, double dy, double dz,
                                  double ax, double ay, double az, long tick, long revision, long continuityEpoch,
                                  double intervalTicks) {
    public RigidMotionSnapshot {
        Objects.requireNonNull(start, "start");
        if (!new Vector3d(dx, dy, dz).isFinite() || !new Vector3d(ax, ay, az).isFinite()
                || !Double.isFinite(new Vector3d(dx, dy, dz).length())
                || !Double.isFinite(new Vector3d(ax, ay, az).length())
                || !Double.isFinite(intervalTicks) || intervalTicks <= 0 || revision < 0 || continuityEpoch < 0
                || !new Vector3d(dx, dy, dz).div(intervalTicks).isFinite()
                || !new Vector3d(ax, ay, az).div(intervalTicks).isFinite())
            throw new IllegalArgumentException("invalid rigid publication");
    }
    public RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, Vector3dc displacement, Vector3dc angularDisplacement,
                               long tick, long revision, long continuityEpoch, double intervalTicks) {
        this(start, displacement.x(), displacement.y(), displacement.z(), angularDisplacement.x(),
                angularDisplacement.y(), angularDisplacement.z(), tick, revision, continuityEpoch, intervalTicks);
    }
    public Vector3d displacementForInterval() { return new Vector3d(dx, dy, dz); }
    public boolean rotating() { return ax != 0 || ay != 0 || az != 0; }
    public boolean moving() { return rotating() || dx != 0 || dy != 0 || dz != 0; }
    public cc.sighs.gravityengine.math.geometry.RigidPose poseAt(double t) {
        requireTime(t);
        if (t == 0) return start;
        Quaterniond q = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(start.orientation());
        if (rotating()) {
            double angle = new Vector3d(ax, ay, az).length();
            q.premul(new Quaterniond().rotationAxis(angle * t, ax / angle, ay / angle, az / angle));
        }
        return new cc.sighs.gravityengine.math.geometry.RigidPose(start.center().fma(t, displacementForInterval()),
                rotating() ? cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(q) : start.orientation());
    }
    public Vector3d velocityAt(Vector3dc worldPoint, double t) {
        Vector3d radius = new Vector3d(worldPoint).sub(poseAt(t).center());
        return new Vector3d(ax, ay, az).cross(radius).add(displacementForInterval()).div(intervalTicks);
    }
    public OrientedBox bodyAt(OrientedBox local, double t) {
        cc.sighs.gravityengine.math.geometry.RigidPose pose = poseAt(t);
        var orientation = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(pose.orientation())
                .mul(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(local.orientation()));
        return new OrientedBox(pose.transformPoint(local.center()), local.halfExtents(),
                start.orientation().equals(cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY) && !rotating()
                        ? local.orientation() : cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(orientation));
    }
    /** Exact extrema enclosure for arcs <= pi, spherical enclosure for longer arcs.
     * Refinement narrows the angular interval; no fixed motion substeps are used. */
    public OrientedBox envelope(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi, new Vector3d());
    }
    /** Proof enclosure in a translating observer's coordinates. Subtracting the
     * observer's interval displacement preserves relative-time correlation at
     * initially touching/outward contacts. It does not change the publication. */
    OrientedBox envelope(OrientedBox local, double lo, double hi, Vector3dc observerDisplacement) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        OrientedBox first = bodyAt(local, lo);
        if (hi == lo) return first;
        Vector3d translation = displacementForInterval().mul(hi - lo).sub(observerDisplacement);
        OrientedBox rotation = first;
        double angle = new Vector3d(ax, ay, az).length() * (hi - lo);
        if (angle > Math.PI) {
            double radius = local.center().length() + local.halfExtents().length();
            rotation = new OrientedBox(poseAt(lo).center(), new Vector3d(radius), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        } else if (rotating()) {
            Vector3d axis = new Vector3d(ax, ay, az).normalize();
            Quaterniond target = new Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z)
                    .mul(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(first.orientation()));
            var pivot = ObbAngularTrajectory.Pivot.at(first, poseAt(lo).center(), first.orientation());
            rotation = new ObbAngularTrajectory(first, target, pivot).envelope(0, 1);
        }
        Vector3d halfTranslation = rotation.worldVectorToLocal(translation, new Vector3d()).absolute().mul(.5);
        return new OrientedBox(rotation.center().fma(.5, translation),
                rotation.halfExtents().add(halfTranslation), rotation.orientation());
    }
    public cc.sighs.gravityengine.math.geometry.Aabb3d sweptBounds(OrientedBox local, double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        if (!rotating()) return bodyAt(local, lo).enclosingAabb()
                .expandTowards(displacementForInterval().mul(hi - lo));
        return envelope(local, lo, hi).enclosingAabb();
    }
    /** Center translation over an explicit normalized interval. Keeps the
     * translation fast path's multiplication order without subtracting two large world positions. */
    public Vector3d centerDisplacement(double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        return displacementForInterval().mul(hi - lo);
    }
    /** Conservative maximum displacement of ANY material point from its initial position. */
    public double maximumPointDisplacement(OrientedBox local) {
        double angle = new Vector3d(ax, ay, az).length();
        return displacementForInterval().length() + (local.center().length() + local.halfExtents().length())
                * (angle >= Math.PI ? 2 : 2 * Math.sin(angle * .5));
    }
    /** Conservative material-point speed in blocks/tick, used to bound CCD brackets. */
    public double maximumPointSpeed(OrientedBox local) {
        return (displacementForInterval().length() + new Vector3d(ax, ay, az).length()
                * (local.center().length() + local.halfExtents().length())) / intervalTicks;
    }
    private static void requireTime(double t) {
        if (!Double.isFinite(t) || t < 0 || t > 1)
            throw new CollisionSceneCoverageException("rigid publication does not cover time " + t);
    }
}
