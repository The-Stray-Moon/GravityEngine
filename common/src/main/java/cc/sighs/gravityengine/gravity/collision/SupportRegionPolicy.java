package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.Sphere3d;

import java.util.Objects;

/** Generic OBB finite-face support and shape-specific closest-point queries.
 * Ordinary capsule support is selected from actual sweeps in FeetSupportQuery. */
public final class SupportRegionPolicy {
    private SupportRegionPolicy() {}

    public static double bottomOffset(OrientedBox body, Vec3d up) {
        Vec3d n = body.worldVectorToLocal(up);
        Vec3d h = body.halfExtents();
        return Math.abs(n.x())*h.x() + Math.abs(n.y())*h.y() + Math.abs(n.z())*h.z();
    }

    /** Clip a finite obstacle face, rather than sampling points on the foot.
     * distance is explicit downward search travel (zero for current support).
     * The thin slab is contact-sized; it cannot admit knee/leg/waist contacts. */
    record FootFaceIntersection(Vec3d witness, double firstDistance) {}

    static FootFaceIntersection footFaceIntersection(OrientedBox body, OrientedBox obstacle,
            int axis, double sign, Vec3d up, double distance) {
        double epsilon = GravityGroundProbe.PROBE_DISTANCE;
        Vec3d h = body.halfExtents(), extent = obstacle.halfExtents();
        Vec3d localUp = body.worldVectorToLocal(up);
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        java.util.List<Vec3d> polygon = new java.util.ArrayList<>();
        for (int corner = 0; corner < 4; corner++) {
            Vec3d local = Vec3d.ZERO.withComponent(
                            axis,
                            sign * extent.component(axis))
                    .withComponent(
                            u,
                            (corner < 2 ? -1 : 1) * extent.component(u))
                    .withComponent(
                            v,
                            (corner == 0 || corner == 3 ? -1 : 1)
                                    * extent.component(v));
            polygon.add(body.worldPointToLocal(
                    obstacle.localPointToWorld(local)));
        }
        for (int a = 0; a < 3; a++) for (int side = -1; side <= 1; side += 2) {
            polygon = clip(polygon, Vec3d.ZERO.withComponent(a, side),
                    h.component(a)
                            + epsilon
                            + Math.max(
                            0,
                            -side * localUp.component(a) * distance
                    ));
        }
        double bottom = -bottomOffset(body, up);
        polygon = clip(polygon, localUp, bottom + epsilon);
        polygon = clip(polygon, localUp.negate(), -bottom + distance + epsilon);
        if (polygon.isEmpty()) return null;
        // A downward search needs the first finite foot/face intersection,
        // not the polygon centroid: on a slope the centroid overstates travel
        // and makes the existing CCD-validated support descent reject itself.
        double highest = Double.NEGATIVE_INFINITY;
        Vec3d witness = Vec3d.ZERO;
        for (Vec3d point : polygon) {
            highest = Math.max(highest, point.dot(localUp));
            witness = witness.add(point);
        }
        // Prefer the foot's projection when it lies on this finite intersection.
        // A clipped polygon's centroid moves sideways as its vertices change,
        // injecting spurious rotational transport even while the foot remains
        // inside the face. First travel remains the highest intersection point.
        Vec3d faceNormal = obstacle.localVectorToWorld(Vec3d.ZERO.withComponent(axis,sign));
        Vec3d foot = body.localPointToWorld(localUp.multiply(bottom));
        Vec3d facePoint = obstacle.localPointToWorld(Vec3d.ZERO.withComponent(axis,sign*extent.component(axis)));
        Vec3d projected = foot.fma(facePoint.subtract(foot).dot(faceNormal)/up.dot(faceNormal),up);
        Vec3d inBody = body.worldPointToLocal(projected);
        Vec3d inFace = obstacle.worldPointToLocal(projected);
        boolean inside = Math.abs(inFace.component(u)) <= extent.component(u)
                && Math.abs(inFace.component(v)) <= extent.component(v)
                && inBody.dot(localUp) <= bottom+epsilon
                && inBody.dot(localUp) >= bottom-distance-epsilon;
        for (int a=0;a<3;a++) for (int side=-1;side<=1;side+=2)
            inside &= side*inBody.component(a) <= h.component(a)+epsilon
                    + Math.max(0,-side*localUp.component(a)*distance);
        if (inside) return new FootFaceIntersection(projected,bottom-highest);
        // An overhanging foot still needs a witness on the real finite face.
        return new FootFaceIntersection(body.localPointToWorld(
                witness.divide(polygon.size())),
                bottom-highest);
    }

    private static java.util.List<Vec3d> clip(java.util.List<Vec3d> polygon,
            Vec3d normal, double bound) {
        if (polygon.isEmpty()) return polygon;
        java.util.List<Vec3d> clipped = new java.util.ArrayList<>();
        Vec3d previous = polygon.get(polygon.size() - 1);
        double before = previous.dot(normal) - bound;
        for (Vec3d current : polygon) {
            double after = current.dot(normal) - bound;
            if ((before <= 0) != (after <= 0))
                clipped.add(previous.lerp(current, before / (before - after)));
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
    static Vec3d closestObstaclePointTo(
            CollisionObstacle obstacle,
            Vec3d point
    ) {
        return closestObstaclePointTo(obstacle, point, 0);
    }

    static Vec3d closestObstaclePointTo(CollisionObstacle obstacle, Vec3d point, double time) {
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(point, "point");
        if (obstacle instanceof BlockObstacle block) {
            return closestPointOnAabb(block.bounds(), point);
        }
        if (obstacle instanceof WorldBorderObstacle border) {
            return closestPointOnAabb(border.bounds(), point);
        }
        if (obstacle instanceof EntityObstacle entity) {
            return entity.exactBodyAt(time).closestPointTo(point);
        }
        if (obstacle instanceof SphereObstacle sphere) {
            return closestPointOnSphere(sphere.sphere(), point);
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    private static Vec3d closestPointOnAabb(
            Aabb3d box,
            Vec3d point
    ) {
        return new Vec3d(
                clamp(point.x(), box.minX(), box.maxX()),
                clamp(point.y(), box.minY(), box.maxY()),
                clamp(point.z(), box.minZ(), box.maxZ())
        );
    }

    private static double clamp(
            double value,
            double min,
            double max
    ) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static Vec3d closestPointOnSphere(
            Sphere3d sphere,
            Vec3d point
    ) {
        Vec3d center = sphere.center();
        Vec3d offset = point.subtract(center);
        double lengthSquared = offset.lengthSquared();
        if (lengthSquared <= 1.0E-18D) {
            /*
             * The query point coincides with the sphere center: every
             * surface point is equally close.  Deterministically report the
             * sphere's own center; the placement rule then rejects the
             * degenerate contact (never grounds on an interior sample).
             */
            return center;
        }
        return offset
                .multiply(sphere.radius() / Math.sqrt(lengthSquared))
                .add(center);
    }

}
