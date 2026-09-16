package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.GeometryTolerance;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** One-way prescribed OBB CCD. Enclosures only prove clearance; every reported
 * plane comes from an exact pair. Ordered interval refinement preserves the
 * earliest event; inability to prove an interval is explicitly indeterminate. */
final class RigidObstacleSweep {
    private static final int MAX_DEPTH = 28;
    private static final int MAX_TESTS = 1024;
    private final OrientedBox actor;
    private final Vector3d movement;
    private final EntityObstacle obstacle;
    private final double start, duration;
    private final ObbQueryContext context;
    private final SweepInitialState initial;
    private final double relativeTravelBound;
    private int tests;

    private RigidObstacleSweep(OrientedBox actor, Vector3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context, SweepInitialState initial) {
        this.actor = actor; this.movement = movement; this.obstacle = obstacle;
        this.start = start; this.duration = duration; this.context = context; this.initial = initial;
        this.relativeTravelBound = movement.length() + duration * obstacle.motion().intervalTicks()
                * obstacle.motion().maximumPointSpeed(obstacle.snapshot().localBody());
    }
    static SweepContactResult sweep(OrientedBox actor, Vector3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context) {
        var overlap = cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(actor),
                context.orientedObstacle(obstacle.bodyAt(start), new Vector3d()), context.scratch());
        SweepInitialState initial = switch (overlap.status()) {
            case SEPARATED -> SweepInitialState.SEPARATED;
            case TOUCHING -> SweepInitialState.TOUCHING;
            case OVERLAPPING -> SweepInitialState.OVERLAPPING;
        };
        var solve = new RigidObstacleSweep(actor, movement, obstacle, start, duration, context, initial);
        if (initial == SweepInitialState.OVERLAPPING) {
            // An angular mover cannot use the translation-only baseline exemption
            // to skip an untested deeper intermediate pose.
            return solve.unknown();
        }
        if (initial == SweepInitialState.TOUCHING) {
            var contacts = solve.contactsForNormal(0, overlap, overlap.normal(new Vector3d()), true);
            if (!contacts.isEmpty()) return new SweepContactResult(initial, contacts);
        }
        return solve.refine(0, 1, 0);
    }
    private SweepContactResult unknown() {
        return new SweepContactResult(SweepInitialState.SEPARATED, List.of(), true);
    }
    private double time(double fraction) { return Math.min(1, start + duration * fraction); }
    private cc.sighs.gravityengine.math.geometry.ObbOverlapResult overlap(OrientedBox a, OrientedBox b) {
        return cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(a), context.orientedObstacle(b, new Vector3d()), context.scratch());
    }
    private SweepContactResult refine(double lo, double hi, int depth) {
        if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
        Vector3d delta = new Vector3d(movement).mul(hi - lo);
        OrientedBox atLo = actor.move(new Vector3d(movement).mul(lo));
        OrientedBox obstacleEnvelope = obstacle.motion().envelope(
                obstacle.snapshot().localBody(), time(lo), time(hi), delta);
        var enclosure = overlap(atLo, obstacleEnvelope);
        if (enclosure.status() != cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.OVERLAPPING)
            return new SweepContactResult(initial, List.of());

        double uncertainty = relativeTravelBound * (hi - lo);
        if (depth >= MAX_DEPTH || hi - lo <= CollisionTolerances.TOI_EPSILON
                && uncertainty <= CollisionTolerances.CONTACT_SKIN) {
            if (uncertainty > CollisionTolerances.CONTACT_SKIN) return unknown();
            if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
            var exact = overlap(actor.move(new Vector3d(movement).mul(hi)), obstacle.bodyAt(time(hi)));
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
        OrientedBox a = actor.move(new Vector3d(movement).mul(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        List<Vector3d> actorAxes = List.of(a.orientation().axisX(new Vector3d()),
                a.orientation().axisY(new Vector3d()), a.orientation().axisZ(new Vector3d()));
        List<Vector3d> obstacleAxes = List.of(b.orientation().axisX(new Vector3d()),
                b.orientation().axisY(new Vector3d()), b.orientation().axisZ(new Vector3d()));
        List<Vector3d> axes = new ArrayList<>(actorAxes);
        axes.addAll(obstacleAxes);
        for (var x : actorAxes) for (var y : obstacleAxes) axes.add(new Vector3d(x).cross(y));
        List<Vector3d> normals = new ArrayList<>();
        normals.add(exact.normal(new Vector3d()));
        var pureA = context.body(a);
        var pureB = context.orientedObstacle(b, new Vector3d());
        for (var axis : axes) {
            if (axis.lengthSquared() <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) continue;
            var along = cc.sighs.gravityengine.math.geometry.ObbSat.overlapAlong(pureA, pureB, axis.normalize());
            if (!along.hasNormal() || along.penetration() > exact.penetration() + band) continue;
            var normal = along.normal(new Vector3d());
            if (normals.stream().noneMatch(n -> CollisionTolerances.sameConstraintIdentity(n, normal)))
                normals.add(normal);
        }
        List<CollisionContact> result = new ArrayList<>();
        for (var normal : normals) result.addAll(contactsForNormal(fraction, exact, normal, false));
        return result;
    }

    private List<CollisionContact> contactsForNormal(double fraction, cc.sighs.gravityengine.math.geometry.ObbOverlapResult exact,
            Vector3d normal, boolean enteringOnly) {
        OrientedBox a = actor.move(new Vector3d(movement).mul(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        // Project the actual actor support feature onto the finite obstacle. A
        // rotating face needs the velocity of each contacted material point.
        Vector3d h = a.halfExtents();
        List<CollisionContact> contacts = new ArrayList<>();
        double minimum = Double.POSITIVE_INFINITY;
        for (int bits = 0; bits < 8; bits++) {
            Vector3d point = a.localPointToWorld(new Vector3d((bits & 1) == 0 ? -h.x : h.x,
                    (bits & 2) == 0 ? -h.y : h.y, (bits & 4) == 0 ? -h.z : h.z), new Vector3d());
            minimum = Math.min(minimum, point.dot(normal));
        }
        for (int bits = 0; bits < 8; bits++) {
            Vector3d corner = a.localPointToWorld(new Vector3d((bits & 1) == 0 ? -h.x : h.x,
                    (bits & 2) == 0 ? -h.y : h.y, (bits & 4) == 0 ? -h.z : h.z), new Vector3d());
            if (corner.dot(normal) > minimum + CollisionTolerances.CONTACT_SKIN) continue;
            Vector3d point = b.closestPointTo(corner);
            if (point.distance(corner) > CollisionTolerances.CONTACT_SKIN + exact.penetration()) continue;
            Vector3d velocity = obstacle.velocityAt(point, time(fraction));
            if (enteringOnly && new Vector3d(movement).sub(new Vector3d(velocity)
                    .mul(duration * obstacle.motion().intervalTicks())).dot(normal)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) continue;
            if (enteringOnly) {
                // One actual shared material point must enter the finite pair,
                // including every simultaneous touching axis. An inward plane
                // alone cannot turn an outward/tangent edge into a sticky hit.
                var relative = new Vector3d(movement).sub(new Vector3d(velocity)
                        .mul(duration * obstacle.motion().intervalTicks()));
                var entry = cc.sighs.gravityengine.math.geometry.ObbSweep.sweep(context.body(a), relative,
                        context.orientedObstacle(b, new Vector3d()), context.scratch());
                if (entry.status() == cc.sighs.gravityengine.math.geometry.ObbSweepResult.Status.NO_HIT) continue;
                for (int axis = 0; axis < entry.activeAxes().size(); axis++) {
                    contacts.add(new CollisionContact(obstacle, point,
                            entry.activeAxes().normal(axis, new Vector3d()), 0, fraction, velocity, time(fraction)));
                }
                continue;
            }
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction, velocity, time(fraction)));
        }
        if (contacts.isEmpty() && !enteringOnly) {
            Vector3d point = b.closestPointTo(a.closestPointTo(b.center()));
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction,
                    obstacle.velocityAt(point, time(fraction)), time(fraction)));
        }
        return contacts;
    }
}
