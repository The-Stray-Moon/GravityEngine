package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Adapts Vanilla block-response scalar/vector carriers without transferring
 * material-policy ownership out of Vanilla.
 *
 * <p>For ordinary movement/control operations, {@link #local} /
 * {@link #world} use the operation reference frame.</p>
 *
 * <p>Landing is different. Vanilla's {@code updateEntityAfterFallOn} and
 * bounce code use world Y because, in Vanilla, the ordinary landing face
 * normal is world +Y. Under arbitrary gravity the semantic axis of that
 * operation is the actual contacted landing face, not GravityFrame.up().
 * Therefore fallLocal/fallWorld use the same-operation gravity-down contact
 * published by the collision route whenever one exists.</p>
 */
public final class VanillaBlockResponse {
    private VanillaBlockResponse() {}

    /**
     * Convert world velocity into the native landing-response carrier.
     *
     * <p>Carrier +Y is the actual contacted support normal. Vanilla's existing
     * zero/negate-Y formulas can therefore execute unchanged.</p>
     */
    public static Vec3 fallLocal(Entity entity, Vec3 world) {
        Quaterniond rotation = fallResponseRotation(entity);

        if (rotation == null) {
            /*
             * No determinate gravity-down contact was published. Preserve the
             * previous reference-space fallback for non-landing vertical
             * callbacks such as ceiling response.
             */
            return local(entity, world);
        }

        Vector3d result =
                new Quaterniond(rotation)
                        .conjugate()
                        .transform(
                                new Vector3d(
                                        world.x,
                                        world.y,
                                        world.z
                                )
                        );

        return new Vec3(
                result.x,
                result.y,
                result.z
        );
    }

    /**
     * Convert a native landing-response carrier back to world velocity.
     */
    public static Vec3 fallWorld(Entity entity, Vec3 local) {
        Quaterniond rotation = fallResponseRotation(entity);

        if (rotation == null) {
            return world(entity, local);
        }

        Vector3d result =
                rotation.transform(
                        new Vector3d(
                                local.x,
                                local.y,
                                local.z
                        )
                );

        return new Vec3(
                result.x,
                result.y,
                result.z
        );
    }

    /**
     * Actual same-operation landing basis.
     *
     * <p>Prefer the gravity-down movement contact because
     * updateEntityAfterFallOn is caused by this move's blocked-down leg.
     * The later tangent leg is allowed to leave that surface, in which case
     * terminal support is empty but the landing callback still requires the
     * original impact normal.</p>
     */
    private static Quaterniond fallResponseRotation(Entity entity) {
        if (!GravityInfluencePolicy.usesCustomCollision(entity)) {
            return null;
        }

        GravityMoveResult move =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime()
                        .currentMoveResult();

        return fallResponseRotation(move);
    }

    /*
     * Package-private for focused tests. Returning null means "no authoritative
     * landing normal; use the existing reference-space fallback".
     */
    static Quaterniond fallResponseRotation(GravityMoveResult move) {
        if (move == null
                || move.indeterminate()
                || !move.blockedDown()) {
            return null;
        }

        /*
         * The vertical-leg contact is the strongest evidence for the native
         * landing callback. Final-body support is only the fallback when the
         * same physical support also survives at the endpoint.
         */
        var contact =
                move.movementSupportContact()
                        .or(move::supportContact);

        if (contact.isEmpty()) {
            return null;
        }

        Vector3d normal =
                contact.orElseThrow().normal();

        return new Quaterniond().rotationTo(
                0.0D,
                1.0D,
                0.0D,
                normal.x,
                normal.y,
                normal.z
        );
    }

    /**
     * Generic environmental-reference carrier used by non-contact-normal
     * Vanilla block expressions.
     */
    public static Vec3 local(Entity entity, Vec3 world) {
        var frame =
                GravityInfluencePolicy.usesCustomCollision(entity)
                        ? cc.sighs.gravityengine.gravity.minecraft
                        .GravityFrameAccess.reliableFrame(entity)
                        : null;

        return frame == null
                ? world
                : frame.worldToLocal(world);
    }

    /**
     * Inverse of {@link #local}.
     */
    public static Vec3 world(Entity entity, Vec3 local) {
        var frame =
                GravityInfluencePolicy.usesCustomCollision(entity)
                        ? cc.sighs.gravityengine.gravity.minecraft
                        .GravityFrameAccess.reliableFrame(entity)
                        : null;

        return frame == null
                ? local
                : frame.localToWorld(local);
    }
}
