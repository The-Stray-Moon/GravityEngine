package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/** Exact support-function helpers for the authoritative locomotion bodies. */
public final class CollisionBodyProjection {
    private static final double UNIT_NORMAL_EPSILON = 1.0E-6D;

    private CollisionBodyProjection() {}

    /** Returns the body's exact projection radius along a world-space unit normal. */
    public static double radiusAlong(CollisionBody body, Vec3d unitNormal) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(unitNormal, "unitNormal");
        requireUnit(unitNormal);
        if (body instanceof CharacterCapsule capsule) {
            return capsule.projectionRadius(unitNormal);
        }
        if (body instanceof OrientedBox box) {
            return box.halfExtents().x()
                    * Math.abs(box.axisX().dot(unitNormal))
                    + box.halfExtents().y()
                    * Math.abs(box.axisY().dot(unitNormal))
                    + box.halfExtents().z()
                    * Math.abs(box.axisZ().dot(unitNormal));
        }
        throw new IllegalStateException(
                "Unhandled collision body type: " + body.getClass().getName()
        );
    }

    private static void requireUnit(Vec3d normal) {
        if (!normal.isFinite()
                || Math.abs(normal.lengthSquared() - 1.0D)
                > UNIT_NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "normal must be finite and normalized: " + normal
            );
        }
    }
}
