package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * An application change is not necessarily a collider discontinuity.
 * This seam changes reference metadata only: never P, AABB, dimensions,
 * velocity, ground/collision flags, support block or fall distance.
 *
 * <p>The gate is the installed {@link BodyRepresentation}, not the movement
 * route and not a {@code custom} flag: a metadata-only commit is legal exactly
 * when the platform AABB is the installed collider on both sides.</p>
 */
public final class NativeAabbApplicationCommit {
    private NativeAabbApplicationCommit() {}

    public static boolean isNativeApplication(
            CommittedGravityApplication application, Vec3d installedUp) {
        Objects.requireNonNull(application, "application");
        if (BodyRepresentation.ofAxis(installedUp)
                != BodyRepresentation.NATIVE_AABB) {
            return false;
        }
        /*
         * The platform collider is the same body either way, but the installed
         * collision axis is still the movement kernel's operand: a custom-body
         * application owns a default-equivalent axis, and a Vanilla plan owns
         * none. A metadata-only commit may not silently drop or invent it.
         */
        return application.plan().usesCustomBody()
                ? installedUp != null
                : installedUp == null;
    }

    /** Called only after Application's existing handoff-authority check. */
    public static boolean tryCommit(Entity entity, CommittedGravityApplication next) {
        if (entity.level().isClientSide()
                || !(entity instanceof Player)
                || !PlayerBodyHandoff.mayChangeBody(entity)) {
            return false;
        }
        Vec3d up = next.plan().usesCustomBody() ? next.appliedState().down().negate() : null;
        if (!isNativeApplication(next, up)) return false;
        if (installedRepresentation(entity) != BodyRepresentation.NATIVE_AABB) return false;
        if (GravityInfluencePolicy.collisionRoute(entity) != GravityCollisionRoute.VANILLA) {
            return false;
        }
        installAuthoritative(entity, next, up);
        return true;
    }

    private static void installAuthoritative(
            Entity entity, CommittedGravityApplication next, Vec3d up) {
        if (!(entity instanceof Player)
                || !isNativeApplication(next, up)
                || installedRepresentation(entity) != BodyRepresentation.NATIVE_AABB) {
            throw new IllegalStateException("metadata-only commit requires native AABB on both sides");
        }
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        var runtime = component.operationState();
        if (runtime.isInMove() || runtime.isApplyingGeometry()) {
            throw new IllegalStateException("application commit inside a geometry/movement operation");
        }
        runtime.clearMovementTransientState();
        if (component.state().commitAuthoritativeApplication(next)
                && entity instanceof ServerPlayer player) {
            PlayerBodyHandoff.markApplicationChanged(player);
        }
        if (up == null) runtime.clearInstalledCollisionAxis();
        else runtime.setInstalledCollisionAxis(up);
        // Intentionally no setPos/setBoundingBox/setOnGround/setDeltaMovement.
    }

    /** The collider currently installed on {@code entity}, not its movement route. */
    public static BodyRepresentation installedRepresentation(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        return BodyRepresentation.ofAxis(
                component.operationState().installedCollisionUp()
        );
    }
}
