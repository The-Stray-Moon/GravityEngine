package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.GeometryTolerance;
import java.util.ArrayList;
import java.util.List;

/** One-way prescribed OBB CCD. Enclosures only prove clearance; every reported
 * plane comes from an exact pair. Ordered interval refinement preserves the
 * earliest event; inability to prove an interval is explicitly indeterminate. */
final class RigidObstacleSweep {
    private static final int MAX_DEPTH = 28;
    private static final int MAX_TESTS = 1024;
    private final OrientedBox actor;
    private final Vec3d movement;
    private final EntityObstacle obstacle;
    private final double start, duration;
    private final ObbQueryContext context;
    private final SweepInitialState initial;
    private final double relativeTravelBound;
    private final double minimumGap;
    private int tests;

    private RigidObstacleSweep(OrientedBox actor, Vec3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context, SweepInitialState initial, double minimumGap) {
        this.actor = actor; this.movement = movement; this.obstacle = obstacle;
        this.start = start; this.duration = duration; this.context = context; this.initial = initial;
        this.minimumGap = minimumGap;
        this.relativeTravelBound = movement.length() + duration * obstacle.motion().intervalTicks()
                * obstacle.motion().maximumPointSpeed(obstacle.snapshot().localBody());
    }
    static SweepContactResult sweep(OrientedBox actor, Vec3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context) {
        var overlap = cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(actor),
                context.orientedObstacle(obstacle.bodyAt(start), Vec3d.ZERO), context.scratch());
        SweepInitialState initial = switch (overlap.status()) {
            case SEPARATED -> SweepInitialState.SEPARATED;
            case TOUCHING -> SweepInitialState.TOUCHING;
            case OVERLAPPING -> SweepInitialState.OVERLAPPING;
        };
        // TOUCHING classifies both a positive gap and a shallow pre-existing
        // overlap. It does not authorize spending the rest of that numerical
        // band on new penetration, which can accumulate until the next tick
        // starts overlapping and rejects all movement on a rising platform.
        double minimumGap = 0;
        if (initial == SweepInitialState.TOUCHING) {
            var axis = overlap.normal();
            var first = context.body(actor);
            var second = context.orientedObstacle(obstacle.bodyAt(start), Vec3d.ZERO);
            minimumGap = Math.min(0, Math.abs(first.center().subtract(second.center()).dot(axis))
                    - first.radiusAlong(axis) - second.radiusAlong(axis));
        }
        var solve = new RigidObstacleSweep(actor, movement, obstacle, start, duration, context, initial, minimumGap);
        if (initial == SweepInitialState.OVERLAPPING) {
            // An angular mover cannot use the translation-only baseline exemption
            // to skip an untested deeper intermediate pose.
            return solve.unknown();
        }
        // Rotation preserves projection onto its own world axis. If that axis
        // separates the pair initially and the relative linear motion is not
        // entering, it proves clearance for the WHOLE interval. This includes
        // tangent travel on a yawing floor: a conservative angular envelope's
        // roundoff must not manufacture a blocking zero-TOI floor contact.
        var single = obstacle.motion().invariantAxisMotion();
        if (single != null) {
            Vec3d axis = new Vec3d(single.ax(), single.ay(), single.az()).normalized();
            var first = context.body(actor);
            var second = context.orientedObstacle(obstacle.bodyAt(start), Vec3d.ZERO);
            double separation = first.center().subtract(second.center()).dot(axis);
            double gap = Math.abs(separation)-first.radiusAlong(axis)-second.radiusAlong(axis);
            double relative = movement.subtract(single.displacementForInterval().multiply(duration))
                    .dot(axis)*Math.copySign(1,separation);
            // Projection onto the fixed rotation axis is affine in time even
            // for normalized-linear rotation. Check both ends of that scalar
            // interval: a slightly entering velocity can still remain clear.
            // This avoids losing a valid near-tangent separator to envelope
            // roundoff without discarding any angular component.
            if (gap+Math.min(0,relative)>=minimumGap-GeometryTolerance.COMPARISON)
                return new SweepContactResult(initial, List.of());
        }
        if (solve.movingPlaneClear(obstacle.motion(), actor, movement, start, duration))
            return new SweepContactResult(initial, List.of());
        if (initial == SweepInitialState.TOUCHING) {
            var contacts = solve.contactsForNormal(0, overlap, overlap.normal(), true);
            if (!contacts.isEmpty()) return new SweepContactResult(initial, contacts);
        }
        return solve.refine(0, 1, 0);
    }
    /** A rotating face is also a separating plane. Bound each fixed actor
     * corner's signed distance in that moving plane, retaining cancellation
     * between translation and rotation. Endpoint chords have error <= max|f''|
     * dt^2/8. This proves near-tangent shallow contacts without consuming the
     * touching band or exhausting subdivision on a loose spatial enclosure. */
    private boolean movingPlaneClear(RigidTrajectory motion, OrientedBox actor, Vec3d movement,
            double start, double duration) {
        if (duration == 0) return false;
        double end = Math.min(1, start + duration);
        if (motion instanceof SampledRigidTrajectory sampled) {
            int count = sampled.segments().size();
            for (int i = 0; i < count; i++) {
                double lo = Math.max(start, (double) i / count);
                double hi = Math.min(end, (double) (i + 1) / count);
                if (hi <= lo) continue;
                if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return false;
                if (!movingPlaneClear(sampled.segments().get(i), actor.move(movement.multiply((lo - start) / duration)),
                        movement.multiply((hi - lo) / duration), Math.max(0, lo * count - i), (hi - lo) * count)) return false;
            }
            return true;
        }
        var single = motion.invariantAxisMotion();
        if (single == null) return false;
        var first = motion.bodyAt(obstacle.snapshot().localBody(), start);
        var last = motion.bodyAt(obstacle.snapshot().localBody(), end);
        var pivot0 = single.poseAt(start).center();
        var pivot1 = single.poseAt(end).center();
        var relativeVelocity = movement.multiply(1 / duration).subtract(single.displacementForInterval());
        var rotationAxis = single.requiresGeneralSweep() ? new Vec3d(single.ax(), single.ay(), single.az()).normalized() : Vec3d.ZERO;
        var axes0 = List.of(first.orientation().axisX(), first.orientation().axisY(), first.orientation().axisZ());
        var axes1 = List.of(last.orientation().axisX(), last.orientation().axisY(), last.orientation().axisZ());
        var extent = first.halfExtents();
        var h = actor.halfExtents();
        for (int axis = 0; axis < 3; axis++) {
            double sign = Math.copySign(1, actor.center().subtract(first.center()).dot(axes0.get(axis)));
            var n0 = axes0.get(axis).multiply(sign);
            var n1 = axes1.get(axis).multiply(sign);
            double radius = axis == 0 ? extent.x() : axis == 1 ? extent.y() : extent.z();
            double perpendicular = n0.cross(rotationAxis).length();
            boolean clear = true;
            for (int bits = 0; bits < 8; bits++) {
                var a = actor.localPointToWorld(new Vec3d((bits & 1) == 0 ? -h.x() : h.x(),
                        (bits & 2) == 0 ? -h.y() : h.y(), (bits & 4) == 0 ? -h.z() : h.z()));
                var b = a.add(movement);
                double gap = Math.min(a.subtract(first.center()).dot(n0), b.subtract(last.center()).dot(n1)) - radius;
                double distance = Math.max(a.distance(pivot0), b.distance(pivot1));
                double acceleration = perpendicular * (single.maximumUnitDirectionAcceleration() * distance
                        + 2 * single.maximumAngularDerivativeU() * relativeVelocity.length());
                if (gap - acceleration * duration * duration / 8 < minimumGap - GeometryTolerance.COMPARISON) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return false;
    }
    private SweepContactResult unknown() {
        if (Boolean.getBoolean("gravityengine.traceRigidUnknown")) System.err.println("RIGID_UNKNOWN initial="+initial+" tests="+tests+" start="+start+" duration="+duration+" actor="+actor+" movement="+movement+" local="+obstacle.snapshot().localBody()+" motion="+describe(obstacle.motion()));
        return new SweepContactResult(SweepInitialState.SEPARATED, List.of(), true);
    }
    private static String describe(RigidTrajectory m) {
        if(m instanceof ComposedRigidTrajectory c) return "compose("+describe(c.parent())+","+describe(c.child())+")";
        if(m instanceof SampledRigidTrajectory s) return "sample("+s.segments().stream().map(RigidObstacleSweep::describe).toList()+")";
        return m+" startQ="+cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(m.start().orientation());
    }
    private double time(double fraction) { return Math.min(1, start + duration * fraction); }
    private cc.sighs.gravityengine.math.geometry.ObbOverlapResult overlap(OrientedBox a, OrientedBox b) {
        return cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(a), context.orientedObstacle(b, Vec3d.ZERO), context.scratch());
    }
    private SweepContactResult refine(double lo, double hi, int depth) {
        if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
        Vec3d delta = movement.multiply(hi - lo);
        OrientedBox atLo = actor.move(movement.multiply(lo));
        OrientedBox obstacleEnvelope = obstacle.motion().envelope(
                obstacle.snapshot().localBody(), time(lo), time(hi), delta);
        var enclosure = overlap(atLo, obstacleEnvelope);
        // Preserve a genuine separator or the exact shallow starting overlap.
        // The classification band is not an extra inward movement allowance.
        boolean clear=enclosure.status()==cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.SEPARATED;
        if(enclosure.status()==cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.TOUCHING) {
            var axis=enclosure.normal();
            var first=context.body(atLo);
            var second=context.orientedObstacle(obstacleEnvelope,Vec3d.ZERO);
            double gap=Math.abs(first.center().subtract(second.center()).dot(axis))
                    -first.radiusAlong(axis)-second.radiusAlong(axis);
            clear=gap>=minimumGap-GeometryTolerance.COMPARISON;
        }
        if(clear) return new SweepContactResult(initial,List.of());

        double uncertainty = relativeTravelBound * (hi - lo);
        if (depth >= MAX_DEPTH || hi - lo <= CollisionTolerances.TOI_EPSILON
                && uncertainty <= CollisionTolerances.CONTACT_SKIN) {
            if (uncertainty > CollisionTolerances.CONTACT_SKIN) return unknown();
            if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
            var exact = overlap(actor.move(movement.multiply(hi)), obstacle.bodyAt(time(hi)));
            if (exact.status() == cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.SEPARATED) return unknown();
            var contacts = contactsAt(hi, exact, uncertainty + GeometryTolerance.TOUCHING);
            if (contacts.isEmpty()) return unknown();
            // lo is the last proven legal instant. Exact geometry at the upper
            // contact bracket supplies normals; bracket width is the existing TOI tolerance.
            var stopped = contacts.stream().map(c -> new CollisionContact(c.obstacle(), c.point(),
                    c.normal(), 0, lo, c.surfaceVelocity(), c.obstacleTime())).toList();
            return new SweepContactResult(initial, stopped);
        }
        double mid = (lo + hi) * .5;
        var first = refine(lo, mid, depth + 1);
        if (first.indeterminate() || first.hasBlockingContacts()) return first;
        return refine(mid, hi, depth + 1);
    }

    private List<CollisionContact> contactsAt(double fraction, cc.sighs.gravityengine.math.geometry.ObbOverlapResult exact, double band) {
        OrientedBox a = actor.move(movement.multiply(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        List<Vec3d> actorAxes = List.of(a.orientation().axisX(),
                a.orientation().axisY(), a.orientation().axisZ());
        List<Vec3d> obstacleAxes = List.of(b.orientation().axisX(),
                b.orientation().axisY(), b.orientation().axisZ());
        List<Vec3d> axes = new ArrayList<>(actorAxes);
        axes.addAll(obstacleAxes);
        for (var x : actorAxes) for (var y : obstacleAxes) axes.add(x.cross(y));
        List<Vec3d> normals = new ArrayList<>();
        normals.add(exact.normal());
        var pureA = context.body(a);
        var pureB = context.orientedObstacle(b, Vec3d.ZERO);
        for (var axis : axes) {
            if (axis.lengthSquared() <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) continue;
            var along = cc.sighs.gravityengine.math.geometry.ObbSat.overlapAlong(pureA, pureB, axis.normalized());
            if (!along.hasNormal() || along.penetration() > exact.penetration() + band) continue;
            var normal = along.normal();
            if (normals.stream().noneMatch(n -> CollisionTolerances.sameConstraintIdentity(n, normal)))
                normals.add(normal);
        }
        List<CollisionContact> result = new ArrayList<>();
        for (var normal : normals) result.addAll(contactsForNormal(fraction, exact, normal, false));
        return result;
    }

    private List<CollisionContact> contactsForNormal(double fraction, cc.sighs.gravityengine.math.geometry.ObbOverlapResult exact,
            Vec3d normal, boolean enteringOnly) {
        OrientedBox a = actor.move(movement.multiply(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        // Project the actual actor support feature onto the finite obstacle. A
        // rotating face needs the velocity of each contacted material point.
        Vec3d h = a.halfExtents();
        List<CollisionContact> contacts = new ArrayList<>();
        double minimum = Double.POSITIVE_INFINITY;
        for (int bits = 0; bits < 8; bits++) {
            Vec3d point = a.localPointToWorld(new Vec3d(
                    (bits & 1) == 0 ? -h.x() : h.x(),
                    (bits & 2) == 0 ? -h.y() : h.y(),
                    (bits & 4) == 0 ? -h.z() : h.z()));
            minimum = Math.min(minimum, point.dot(normal));
        }
        for (int bits = 0; bits < 8; bits++) {
            Vec3d corner = a.localPointToWorld(new Vec3d(
                    (bits & 1) == 0 ? -h.x() : h.x(),
                    (bits & 2) == 0 ? -h.y() : h.y(),
                    (bits & 4) == 0 ? -h.z() : h.z()));
            if (corner.dot(normal) > minimum + CollisionTolerances.CONTACT_SKIN) continue;
            Vec3d point = b.closestPointTo(corner);
            if (point.distance(corner) > CollisionTolerances.CONTACT_SKIN + exact.penetration()) continue;
            Vec3d velocity = obstacle.velocityAt(point, time(fraction));
            if (enteringOnly && movement.subtract(velocity
                    .multiply(duration * obstacle.motion().intervalTicks())).dot(normal)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) continue;
            if (enteringOnly) {
                // One actual shared material point must enter the finite pair,
                // including every simultaneous touching axis. An inward plane
                // alone cannot turn an outward/tangent edge into a sticky hit.
                var relative = movement.subtract(velocity
                        .multiply(duration * obstacle.motion().intervalTicks()));
                var entry = cc.sighs.gravityengine.math.geometry.ObbSweep.sweep(context.body(a), relative,
                        context.orientedObstacle(b, Vec3d.ZERO), context.scratch());
                if (entry.status() == cc.sighs.gravityengine.math.geometry.ObbSweepResult.Status.NO_HIT) continue;
                for (int axis = 0; axis < entry.activeAxes().size(); axis++) {
                    contacts.add(new CollisionContact(obstacle, point,
                            entry.activeAxes().normal(axis), 0, fraction, velocity, time(fraction)));
                }
                continue;
            }
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction, velocity, time(fraction)));
        }
        if (contacts.isEmpty() && !enteringOnly) {
            Vec3d point = b.closestPointTo(a.closestPointTo(b.center()));
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction,
                    obstacle.velocityAt(point, time(fraction)), time(fraction)));
        }
        return contacts;
    }
}
