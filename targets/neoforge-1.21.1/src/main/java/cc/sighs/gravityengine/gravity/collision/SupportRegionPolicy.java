package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.Sphere3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/** Generic OBB finite-face support and shape-specific closest-point queries.
 * Ordinary capsule support is selected from actual sweeps in FeetSupportQuery. */
public final class SupportRegionPolicy {
    private SupportRegionPolicy() {}

    public static double bottomOffset(OrientedBox body, Vector3d up) {
        Vector3d n = body.worldVectorToLocal(up, new Vector3d());
        Vector3d h = body.halfExtents();
        return Math.abs(n.x)*h.x + Math.abs(n.y)*h.y + Math.abs(n.z)*h.z;
    }

    /** Clip a finite obstacle face, rather than sampling points on the foot.
     * distance is explicit downward search travel (zero for current support).
     * The thin slab is contact-sized; it cannot admit knee/leg/waist contacts. */
    record FootFaceIntersection(Vector3d witness, double firstDistance) {}

    static FootFaceIntersection footFaceIntersection(OrientedBox body, OrientedBox obstacle,
            int axis, double sign, Vector3d up, double distance) {
        double epsilon = GravityGroundProbe.PROBE_DISTANCE;
        Vector3d h = body.halfExtents(), extent = obstacle.halfExtents();
        Vector3d localUp = body.worldVectorToLocal(up, new Vector3d());
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        java.util.List<Vector3d> polygon = new java.util.ArrayList<>();
        for (int corner = 0; corner < 4; corner++) {
            Vector3d local = new Vector3d().setComponent(axis, sign * extent.get(axis))
                    .setComponent(u, (corner < 2 ? -1 : 1) * extent.get(u))
                    .setComponent(v, (corner == 0 || corner == 3 ? -1 : 1) * extent.get(v));
            polygon.add(body.worldPointToLocal(obstacle.localPointToWorld(local, new Vector3d()), new Vector3d()));
        }
        for (int a = 0; a < 3; a++) for (int side = -1; side <= 1; side += 2) {
            polygon = clip(polygon, new Vector3d().setComponent(a, side),
                    h.get(a) + epsilon + Math.max(0, -side * localUp.get(a) * distance));
        }
        double bottom = -bottomOffset(body, up);
        polygon = clip(polygon, localUp, bottom + epsilon);
        polygon = clip(polygon, new Vector3d(localUp).negate(), -bottom + distance + epsilon);
        if (polygon.isEmpty()) return null;
        // A downward search needs the first finite foot/face intersection,
        // not the polygon centroid: on a slope the centroid overstates travel
        // and makes the existing CCD-validated support descent reject itself.
        double highest = Double.NEGATIVE_INFINITY;
        Vector3d witness = new Vector3d();
        for (Vector3d point : polygon) {
            highest = Math.max(highest, point.dot(localUp));
            witness.add(point);
        }
        // Keep the interior material-point witness separate from first travel.
        // An arbitrary extreme vertex changes rotational carry and can lie
        // behind a neighboring wall even when the finite face is exposed.
        return new FootFaceIntersection(body.localPointToWorld(witness.div(polygon.size()),new Vector3d()),
                bottom-highest);
    }

    private static java.util.List<Vector3d> clip(java.util.List<Vector3d> polygon,
            Vector3d normal, double bound) {
        if (polygon.isEmpty()) return polygon;
        java.util.List<Vector3d> clipped = new java.util.ArrayList<>();
        Vector3d previous = polygon.getLast();
        double before = previous.dot(normal) - bound;
        for (Vector3d current : polygon) {
            double after = current.dot(normal) - bound;
            if ((before <= 0) != (after <= 0))
                clipped.add(new Vector3d(previous).lerp(current, before / (before - after)));
            if (after <= 0) clipped.add(current);
            previous = current;
            before = after;
        }
        return clipped;
    }

    /**
     * Exact closest point on the obstacle's surface/interior to a world
     * point.  Pure geometry per obstacle shape; no obstacle type ever
     * selects support semantics.
     */
    static Vector3d closestObstaclePointTo(
            CollisionObstacle obstacle,
            Vector3dc point
    ) {
        return closestObstaclePointTo(obstacle, point, 0);
    }

    static Vector3d closestObstaclePointTo(CollisionObstacle obstacle, Vector3dc point, double time) {
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(point, "point");
        return switch (obstacle) {
            case BlockObstacle block -> closestPointOnAabb(
                    block.bounds(), point);
            case WorldBorderObstacle border -> closestPointOnAabb(
                    border.bounds(), point);
            case EntityObstacle entity -> entity.exactBodyAt(time).closestPointTo(point);
            case SphereObstacle sphere -> closestPointOnSphere(
                    sphere.sphere(), point);
        };
    }

    private static Vector3d closestPointOnAabb(
            Aabb3d box,
            Vector3dc point
    ) {
        return new Vector3d(
                clamp(point.x(), box.minX(), box.maxX()),
                clamp(point.y(), box.minY(), box.maxY()),
                clamp(point.z(), box.minZ(), box.maxZ())
        );
    }

    private static Vector3d closestPointOnSphere(
            Sphere3d sphere,
            Vector3dc point
    ) {
        Vector3d center = sphere.center();
        Vector3d offset = new Vector3d(point).sub(center);
        double lengthSquared = offset.lengthSquared();
        if (lengthSquared <= 1.0E-18D) {
            /*
             * The query point coincides with the sphere center: every
             * surface point is equally close.  Deterministically report the
             * sphere's own center; the placement rule then rejects the
             * degenerate contact (never grounds on an interior sample).
             */
            return new Vector3d(center);
        }
        return offset
                .mul(sphere.radius() / Math.sqrt(lengthSquared))
                .add(center);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
