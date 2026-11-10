package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;

/** Internal immutable motion evidence. Times are normalized to one captured
 * interval; velocities are blocks/tick. No implementation owns live physics.
 * A fast-path proof requires fixed orientation and linear translation. */
public sealed interface RigidTrajectory permits RigidMotionSnapshot, ComposedRigidTrajectory, SampledRigidTrajectory {
    long tick();
    long revision();
    long continuityEpoch();
    double intervalTicks();
    /** False proves fixed orientation plus linear translation. True conservatively
     * requires the general sweep; it does not assert physical rotation. */
    boolean requiresGeneralSweep();
    boolean moving();
    RigidPose poseAt(double time);
    default RigidPose start() { return poseAt(0); }
    Vec3d velocityAt(Vec3d worldPoint, double time);
    OrientedBox bodyAt(OrientedBox local, double time);
    OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observerDisplacement);
    default OrientedBox envelope(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi, Vec3d.ZERO);
    }
    default Aabb3d sweptBounds(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi).enclosingAabb();
    }
    Vec3d centerDisplacement(double lo, double hi);
    default Vec3d displacementForInterval() { return centerDisplacement(0, 1); }
    double maximumPointDisplacement(OrientedBox local);
    /** Maximum material-point speed in blocks per game tick. */
    double maximumPointSpeed(OrientedBox local);
    /** Angular derivative bound in radians per normalized u. */
    double maximumAngularDerivativeU();
    /** Material-point second derivative bound in blocks/u squared on each smooth
     * piece. Velocity knots have no finite classical second derivative: chord
     * proofs must split at subdivisionMultiple() knots, not cross them. */
    double maximumPointSecondDerivativeU(OrientedBox local);
    /** Optional proof of one invariant world rotation axis plus linear pivot
     * translation. Static coordinate rebasing preserves this proof. */
    default RigidMotionSnapshot invariantAxisMotion() { return null; }
    /** Uniform subdivision must include these captured velocity discontinuities. */
    default int subdivisionMultiple() { return 1; }
}
