package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Optional Sable compatibility facade.
 *
 * <p>This class has no Sable type in its signatures. The version-specific
 * adapter is loaded only after Sable's root class is visible, so GravityEngine
 * remains loadable without Sable installed.</p>
 */
public final class SableMovementCompatibility {
    private static final String SABLE_ROOT = "dev.ryanhcode.sable.Sable";
    private static final boolean AVAILABLE = probe();

    private SableMovementCompatibility() {}

    private static boolean probe() {
        try {
            Class.forName(
                    SABLE_ROOT,
                    false,
                    SableMovementCompatibility.class.getClassLoader()
            );
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    public static boolean available() {
        return AVAILABLE;
    }
    public static Vec3 projectOut(Entity actor,Vec3 position) {
        return AVAILABLE?dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(actor.level(),position):position;
    }

    public static void captureEnvironment(cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        if (AVAILABLE) SableRigidCollisionProvider.captureEnvironment(result);
    }
    public static java.util.List<cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox> fluidOcclusion(
            cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        return AVAILABLE?SableRigidCollisionProvider.fluidOcclusion(result):java.util.List.of();
    }

    public record ParentMotion(cc.sighs.gravityengine.gravity.collision.RigidTrajectory trajectory,
                               cc.sighs.gravityengine.api.math.Vec3d plotOrigin, java.util.UUID identity) {}
    public static java.util.Optional<ParentMotion> parentMotion(Entity entity,
            cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext time) {
        return AVAILABLE ? SableRigidCollisionProvider.parentMotion(entity, time) : java.util.Optional.empty();
    }

    public static boolean isPlotPosition(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        return AVAILABLE && SablePlotCoordinates.contains(level, pos);
    }

    public static void unload(net.minecraft.world.level.Level level) {
        if (AVAILABLE) SableRigidCollisionProvider.unload(level);
    }

    public static java.util.Optional<cc.sighs.gravityengine.gravity.integration.BlockContactResolver.Resolved>
    resolveBlockContact(Entity entity, cc.sighs.gravityengine.gravity.collision.GravitySupportContact contact) {
        return AVAILABLE ? SableRigidCollisionProvider.resolveBlockContact(entity, contact) : java.util.Optional.empty();
    }

    /** Captures the completed SubLevel result exactly as it enters the parent solve. */
    public static ExternalSubLevelMoveEvidence capture(
            Entity entity,
            Vec3 parentSolverMovement
    ) {
        if (!AVAILABLE) {
            return null;
        }
        return SableMovementAdapter.capture(
                entity,
                parentSolverMovement
        );
    }

    /**
     * Undo Sable's world-Y-only tracking clear when its own SubLevel solve
     * still selected the same tracking body. This runs only while GravityEngine
     * owns the custom parent-world collision route.
     */
    public static void restoreTrackingAfterParentWorldWorldYChange(
            Entity entity
    ) {
        if (AVAILABLE) {
            SableMovementAdapter
                    .restoreTrackingAfterParentWorldWorldYChange(entity);
        }
    }

    public static boolean isInheritedSupportTransport(
            Entity entity,
            Vec3 movement
    ) {
        return AVAILABLE
                && SableMovementAdapter
                .isInheritedSupportTransport(entity, movement);
    }

    public static boolean finalizedMinorHorizontalCollision(
            Entity entity,
            boolean baseMinorHorizontalCollision
    ) {
        return AVAILABLE
                ? SableMovementAdapter.finalizedMinorHorizontalCollision(
                entity,
                baseMinorHorizontalCollision
        )
                : baseMinorHorizontalCollision;
    }
}
