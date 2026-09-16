package cc.sighs.gravityengine.gravity.minecraft.geometry;

import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;

/** Exact support-function helpers for the authoritative locomotion bodies. */
public final class CollisionBodyProjection {
    private static final double UNIT_NORMAL_EPSILON = 1.0E-6D;

    private CollisionBodyProjection() {}

    /** Returns the body's exact projection radius along a world-space unit normal. */
    public static double radiusAlong(CollisionBody body, Vec3 unitNormal) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(unitNormal, "unitNormal");
        requireUnit(unitNormal);
        Vector3d normal = MinecraftGeometryAdapter.toJoml(
                unitNormal, new Vector3d());
        return switch (body) {
            case CharacterCapsule capsule -> capsule.projectionRadius(normal);
            case OrientedBox box -> box.halfExtents().x
                    * Math.abs(box.axisX().dot(normal))
                    + box.halfExtents().y
                    * Math.abs(box.axisY().dot(normal))
                    + box.halfExtents().z
                    * Math.abs(box.axisZ().dot(normal));
        };
    }

    private static void requireUnit(Vec3 normal) {
        if (!Double.isFinite(normal.x)
                || !Double.isFinite(normal.y)
                || !Double.isFinite(normal.z)
                || Math.abs(normal.lengthSqr() - 1.0D) > UNIT_NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "normal must be finite and normalized: " + normal
            );
        }
    }
}
