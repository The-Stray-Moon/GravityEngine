package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import java.util.Objects;

/** Child motion in parent coordinates, followed by parent motion in world
 * coordinates. The publisher owns the composite revision and continuity epoch;
 * replacement of either component must invalidate composite continuity. */
public record ComposedRigidTrajectory(RigidTrajectory parent, RigidTrajectory child,
        long revision, long continuityEpoch) implements RigidTrajectory {
    public ComposedRigidTrajectory {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
        if (parent.tick() != child.tick() || parent.intervalTicks() != child.intervalTicks())
            throw new CollisionSceneCoverageException("composed motion requires the same captured interval");
        if (revision < 0 || continuityEpoch < 0)
            throw new IllegalArgumentException("negative composed publication identity");
    }
    @Override public long tick() { return parent.tick(); }
    @Override public double intervalTicks() { return parent.intervalTicks(); }
    @Override public int subdivisionMultiple() {
        int a=parent.subdivisionMultiple(),b=child.subdivisionMultiple(),x=a,y=b;
        while (y!=0) { int remainder=x%y; x=y; y=remainder; }
        long multiple=(long)(a/x)*b;
        return multiple>=Integer.MAX_VALUE?Integer.MAX_VALUE:(int)multiple;
    }
    @Override public boolean requiresGeneralSweep() { return parent.requiresGeneralSweep() || child.requiresGeneralSweep(); }
    @Override public boolean moving() { return parent.moving() || child.moving(); }
    @Override public RigidPose poseAt(double time) {
        var p = parent.poseAt(time);
        var c = child.poseAt(time);
        return new RigidPose(p.transformPoint(c.center()), BodyOrientation3d.frame(
                BodyOrientation3d.quaternion(p.orientation())
                        .multiply(BodyOrientation3d.quaternion(c.orientation()))));
    }
    @Override public Vec3d velocityAt(Vec3d worldPoint, double time) {
        var p = parent.poseAt(time);
        var rotation = BodyOrientation3d.quaternion(p.orientation());
        var parentPoint = rotation.conjugate().transform(worldPoint.subtract(p.center()));
        return parent.velocityAt(worldPoint, time)
                .add(rotation.transform(child.velocityAt(parentPoint, time)));
    }
    @Override public OrientedBox bodyAt(OrientedBox local, double time) {
        return parent.bodyAt(child.bodyAt(local, time), time);
    }
    @Override public OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observer) {
        if (!child.moving()) return parent.envelope(child.bodyAt(local, 0), lo, hi, observer);
        // Independent nested enclosures are conservative even when the component
        // rotations have different axes. Refinement shrinks both time intervals.
        // The world observer belongs to the outer trajectory. Passing it into
        // that enclosure preserves its time correlation with parent translation;
        // adding its independent swept extent afterwards cannot prove tangent
        // clearance even when observer and parent translate identically.
        return parent.envelope(child.envelope(local, lo, hi), lo, hi, observer);
    }
    @Override public Vec3d centerDisplacement(double lo, double hi) {
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        return poseAt(hi).center().subtract(poseAt(lo).center());
    }
    @Override public double maximumPointDisplacement(OrientedBox local) {
        if (!child.moving()) return parent.maximumPointDisplacement(child.bodyAt(local, 0));
        return maximumPointSpeed(local) * intervalTicks();
    }
    @Override public RigidMotionSnapshot invariantAxisMotion() {
        return child.moving() ? null : parent.invariantAxisMotion();
    }
    @Override public double maximumPointSpeed(OrientedBox local) {
        return parent.maximumPointSpeed(child.envelope(local, 0, 1)) + child.maximumPointSpeed(local);
    }
    @Override public double maximumAngularDerivativeU() {
        return parent.maximumAngularDerivativeU() + child.maximumAngularDerivativeU();
    }
    @Override public double maximumPointSecondDerivativeU(OrientedBox local) {
        // Acceleration of a moving child point in the rotating parent frame:
        // parent material acceleration + Coriolis term + rotated child acceleration.
        return parent.maximumPointSecondDerivativeU(child.envelope(local, 0, 1))
                + 2 * parent.maximumAngularDerivativeU() * child.maximumPointSpeed(local) * intervalTicks()
                + child.maximumPointSecondDerivativeU(local);
    }
}
