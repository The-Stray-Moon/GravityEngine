package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * An application change is not necessarily a collider discontinuity.
 * This seam changes reference metadata only: never P, AABB, dimensions,
 * velocity, ground/collision flags, support block or fall distance.
 */
public final class NativeAabbApplicationCommit {
    private NativeAabbApplicationCommit() {}

    public static boolean isNativeApplication(
            CommittedGravityApplication application, GravityFrame installedFrame) {
        Objects.requireNonNull(application, "application");
        return switch (application.plan().kind()) {
            case VANILLA -> installedFrame == null;
            case CHARACTER -> installedFrame != null
                    && !GravityInfluencePolicy.requiresReferenceGeometry(installedFrame);
            default -> false;
        };
    }

    /** Called only after Application's existing handoff-authority check. */
    public static boolean tryCommit(Entity entity, CommittedGravityApplication next) {
        if (!(entity instanceof Player)
                || !PlayerBodyHandoff.mayChangeBody(entity)
                || GravityInfluencePolicy.collisionRoute(entity)
                        != GravityInfluencePolicy.CollisionRoute.VANILLA) {
            return false;
        }
        GravityFrame frame = next.plan().usesCustomBody()
                ? GravityEntityGeometry.frameAtPositionAnchor(
                        entity, next.appliedState(), entity.position())
                : null;
        if (!isNativeApplication(next, frame)) return false;
        installCommitted(entity, next, frame);
        return true;
    }

    /** Client calls this only for an explicit server native-application commit. */
    public static void installCommitted(
            Entity entity, CommittedGravityApplication next, GravityFrame frame) {
        if (!(entity instanceof Player)
                || !isNativeApplication(next, frame)
                || GravityInfluencePolicy.collisionRoute(entity)
                        != GravityInfluencePolicy.CollisionRoute.VANILLA) {
            throw new IllegalStateException("metadata-only commit requires native AABB on both sides");
        }
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        var runtime = component.runtime();
        if (runtime.isInMove() || runtime.isApplyingGeometry()) {
            throw new IllegalStateException("application commit inside a geometry/movement operation");
        }
        runtime.clearInfluenceTransientState();
        component.commitApplication(next);
        if (frame == null) runtime.clearInstalledCollisionAxis();
        else runtime.setInstalledCollisionAxisFromFrame(frame);
        // Intentionally no setPos/setBoundingBox/setOnGround/setDeltaMovement.
    }
}
