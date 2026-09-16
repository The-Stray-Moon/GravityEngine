package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Narrow representation adapter for Sable 2.0.5's existing SubLevel
 * character collision solver.
 *
 * <p>Sable remains authoritative for SubLevel discovery, voxel geometry,
 * SAT/MTV response, tracking and inherited rigid-space motion. GravityEngine
 * supplies only the character representation required by that solver.</p>
 */
public final class SablePlayerCollisionCompatibility {
    private static final Vector3d CANONICAL_UP =
            new Vector3d(0.0D, 1.0D, 0.0D);

    private SablePlayerCollisionCompatibility() {
    }

    public static boolean usesCustomCollisionRepresentation(
            Entity entity
    ) {
        return GravityInfluencePolicy.usesCustomBody(entity);
    }

    /**
     * Sable 2.0.5's ServerPlayer shortcut performs no SubLevel narrow phase.
     * A custom GravityEngine character must therefore use Sable's normal solver.
     */
    public static boolean requiresFullServerPlayerCollision(
            Entity entity
    ) {
        return entity instanceof ServerPlayer
                && usesCustomCollisionRepresentation(entity);
    }

    /**
     * Collision-only orientation supplied to Sable.
     *
     * <p>The GravityEngine character is axially symmetric. Only the physical
     * gravity-up axis is supplied. A square Sable OBB is not continuously
     * yaw-symmetric like GravityEngine's capsule; the broad phase must contain
     * every tangent twist used by Sable.</p>
     */
    public static Quaterniondc collisionOrientation(
            Entity entity
    ) {
        if (!usesCustomCollisionRepresentation(entity)) {
            return null;
        }

        GravityFrame frame =
                requireCollisionFrame(entity);

        Vec3 up = frame.up();

        Vector3d worldUp =
                new Vector3d(
                        up.x,
                        up.y,
                        up.z
                );

        if (!worldUp.isFinite()
                || !(worldUp.lengthSquared() > 1.0E-24D)) {
            throw new IllegalStateException(
                    "invalid GravityEngine collision up for Sable: "
                            + up
            );
        }

        worldUp.normalize();

        return new Quaterniond()
                .rotationTo(
                        new Vector3d(CANONICAL_UP),
                        worldUp
                )
                .normalize();
    }

    /**
     * Discovery bounds only. Preserve the original world proxy and also
     * enclose every orientation of Sable's width x height x width box.
     * A capsule proxy alone need not contain the corners of that box.
     */
    public static AABB collisionBroadPhaseBounds(Entity entity, AABB original) {
        if (!usesCustomCollisionRepresentation(entity)) {
            return original;
        }

        GravityFrame frame = requireCollisionFrame(entity);
        EntityDimensions dimensions = GravityEntityGeometry.dimensions(entity);
        double width = dimensions.width();
        double height = dimensions.height();
        requireDimensions(width, height);

        Vec3 center = GravityEntityGeometry.bodyCenter(entity, frame);
        double radius = Math.hypot(height * 0.5D, width / Math.sqrt(2.0D))
                + 1.0E-7D;
        AABB allOrientations = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );
        return original.minmax(allOrientations);
    }

    /**
     * Returns Sable's local width x height x width dimension carrier.
     *
     * <p>The public Entity AABB under custom gravity is only an enclosing
     * broad-phase proxy and must not become Sable's exact local dimensions.</p>
     */
    public static AABB collisionDimensionCarrier(
            Entity entity,
            AABB original
    ) {
        if (!usesCustomCollisionRepresentation(entity)) {
            return original;
        }

        GravityFrame frame =
                requireCollisionFrame(entity);

        EntityDimensions dimensions =
                GravityEntityGeometry.dimensions(entity);

        double width = dimensions.width();
        double height = dimensions.height();

        requireDimensions(
                width,
                height
        );

        Vec3 center =
                GravityEntityGeometry.bodyCenter(
                        entity,
                        frame
                );

        double halfWidth = width * 0.5D;
        double halfHeight = height * 0.5D;

        return new AABB(
                center.x - halfWidth,
                center.y - halfHeight,
                center.z - halfWidth,
                center.x + halfWidth,
                center.y + halfHeight,
                center.z + halfWidth
        );
    }

    /**
     * GravityEngine-compatible feet/reference anchor for Sable collision.
     *
     * <p>Sable's native custom-orientation implementation derives this from
     * eye height. That is not GravityEngine's character model. GravityEngine owns
     * gravity-feet as:</p>
     *
     * <pre>
     * C  = P + WORLD_UP * h/2
     * Fg = C + gravityDown * h/2
     * </pre>
     */
    public static Vector3d collisionFeet(
            Entity entity,
            float distanceDown,
            Vector3d original
    ) {
        if (!usesCustomCollisionRepresentation(entity)) {
            return original;
        }

        if (!Float.isFinite(distanceDown)) {
            throw new IllegalArgumentException(
                    "distanceDown must be finite: "
                            + distanceDown
            );
        }

        GravityFrame frame =
                requireCollisionFrame(entity);

        Vec3 feet =
                GravityEntityGeometry.gravityFeet(
                        entity,
                        frame
                );

        if (distanceDown != 0.0F) {
            feet = feet.add(
                    frame.down()
                            .scale(distanceDown)
            );
        }

        return new Vector3d(
                feet.x,
                feet.y,
                feet.z
        );
    }

    /**
     * Converts Sable's temporary tracking gravity-feet position back into
     * GravityEngine's authoritative Vanilla position anchor P.
     *
     * <p>Sable's tracked-body code computes center - up*h/2 and writes that
     * through sable$setPosSuperRaw(). For a GravityEngine character that value is
     * Fg, not Entity.position(). Keep the temporary Entity state expressed in
     * GravityEngine's real P representation while Sable performs its solve.</p>
     */
    public static Vec3 positionAnchorFromCollisionFeet(
            Entity entity,
            Vec3 gravityFeet
    ) {
        if (!usesCustomCollisionRepresentation(entity)) {
            return gravityFeet;
        }

        if (!finite(gravityFeet)) {
            throw new IllegalArgumentException(
                    "gravityFeet must be finite: "
                            + gravityFeet
            );
        }

        GravityFrame frame =
                requireCollisionFrame(entity);

        EntityDimensions dimensions =
                GravityEntityGeometry.dimensions(entity);

        double height = dimensions.height();

        requireDimensions(
                dimensions.width(),
                height
        );

        Vec3 center =
                gravityFeet.add(
                        frame.up()
                                .scale(height * 0.5D)
                );

        return GravityEntityGeometry
                .positionAnchorFromBodyCenter(
                        center,
                        height
                );
    }

    private static GravityFrame requireCollisionFrame(
            Entity entity
    ) {
        GravityFrame frame =
                collisionFrame(entity);

        if (frame == null) {
            throw new IllegalStateException(
                    "custom GravityEngine character has no collision frame"
            );
        }

        return frame;
    }

    private static GravityFrame collisionFrame(
            Entity entity
    ) {
        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        /*
         * Sable's collision is normally called from inside the already
         * prepared Entity.move operation. Never resample gravity here.
         */
        if (runtime.isInMove()) {
            return runtime.activeFrame();
        }

        /*
         * Focused tests and unusual compatibility calls may occur outside an
         * active move. Consume installed geometry state only.
         */
        return runtime.geometryReferenceFrame();
    }

    private static void requireDimensions(
            double width,
            double height
    ) {
        if (!Double.isFinite(width)
                || !Double.isFinite(height)
                || !(width > 0.0D)
                || !(height > 0.0D)) {
            throw new IllegalStateException(
                    "invalid character dimensions for Sable: "
                            + width + " x " + height
            );
        }
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }
}
