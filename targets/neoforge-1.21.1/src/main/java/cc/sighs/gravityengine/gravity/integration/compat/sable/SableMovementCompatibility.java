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
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    public static boolean available() {
        return AVAILABLE;
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
