package cc.sighs.gravityengine.gravity.collision;

import org.joml.Vector3d;

import java.util.Objects;

/**
 * Structured contact between the entity body and a collision obstacle.
 *
 * @param obstacle       the obstacle involved
 * @param point finite world-space representative point associated with this
 *              contact. It is not universally a geometric plane witness:
 *              swept OBB contacts may use an approximate representative point.
 *              Callers requiring cross-tick plane authority must reconstruct
 *              or validate the witness from obstacle geometry.
 * @param normal         normalized normal pointing from obstacle toward entity
 * @param penetration    penetration depth (non-negative)
 * @param timeOfImpact   time of impact in [0, 1]; 0 for static overlap contacts
 * @param surfaceVelocity blocks/tick at the material contact point and obstacleTime
 * @param obstacleTime absolute normalized instant in the captured operation, distinct from segment-local TOI
 *
 * <p>This kernel record uses only neutral JOML vectors; every accessor
 * returns a fresh defensive copy.</p>
 */
public record CollisionContact(
        CollisionObstacle obstacle,
        Vector3d point,
        Vector3d normal,
        double penetration,
        double timeOfImpact,
        Vector3d surfaceVelocity,
        double obstacleTime
) {
    /** Static/test construction at operation origin. Dynamic producers always supply time. */
    public CollisionContact(CollisionObstacle obstacle, Vector3d point, Vector3d normal,
            double penetration, double timeOfImpact, Vector3d surfaceVelocity) {
        this(obstacle, point, normal, penetration, timeOfImpact, surfaceVelocity, 0);
    }

    private static final double EPSILON = 1.0E-6D;

    public CollisionContact {
        Objects.requireNonNull(obstacle, "obstacle");
        if (!Double.isFinite(obstacleTime) || obstacleTime < 0 || obstacleTime > 1)
            throw new IllegalArgumentException("normalized obstacle time outside publication");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(surfaceVelocity, "surfaceVelocity");
        if (!isFinite(normal)) throw new IllegalArgumentException("normal must be finite: " + normal);
        if (Math.abs(normal.lengthSquared() - 1.0D) > EPSILON) {
            throw new IllegalArgumentException("normal must be normalized: " + normal);
        }
        if (!Double.isFinite(penetration) || penetration < 0.0D)
            throw new IllegalArgumentException("penetration must be finite and non-negative: " + penetration);
        if (!Double.isFinite(timeOfImpact) || timeOfImpact < 0.0D || timeOfImpact > 1.0D)
            throw new IllegalArgumentException("timeOfImpact must be in [0, 1]: " + timeOfImpact);
        if (!isFinite(point)) throw new IllegalArgumentException("point must be finite: " + point);
        if (!isFinite(surfaceVelocity)) {
            throw new IllegalArgumentException("surfaceVelocity must be finite: " + surfaceVelocity);
        }
        point = new Vector3d(point);
        normal = new Vector3d(normal);
        surfaceVelocity = new Vector3d(surfaceVelocity);
    }

    @Override
    public Vector3d point() {
        return new Vector3d(point);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }

    @Override
    public Vector3d surfaceVelocity() {
        return new Vector3d(surfaceVelocity);
    }

    private static boolean isFinite(Vector3d v) {
        return v != null
                && Double.isFinite(v.x)
                && Double.isFinite(v.y)
                && Double.isFinite(v.z);
    }
}
