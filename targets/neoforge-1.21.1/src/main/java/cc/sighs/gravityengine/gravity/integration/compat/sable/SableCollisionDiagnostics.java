package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;

/** Loaded by the optional Sable mixin only. Observation, never collision policy. */
public final class SableCollisionDiagnostics {
    private SableCollisionDiagnostics() {}

    public static void logReturn(
            Entity entity, Object result, String exitReason, int candidateCount
    ) {
        if (!GravityDebugLog.ENABLED
                || !(result instanceof SubLevelEntityCollision.CollisionInfo info)) {
            return;
        }
        int count = info.firstCollisions == null ? -1 : info.firstCollisions.size();
        String exit = "FULL_SOLVER".equals(exitReason)
                ? (count > 0 ? "FULL_WITH_CONTACT" : "FULL_WITHOUT_PUBLISHED_CONTACT")
                : exitReason;
        GravityDebugLog.log(
                entity,
                "sable-collision-return",
                "collisionInfoId=%08x customBody=%s exitReason=%s "
                        + "finalCandidateCount=%d firstCollisionsNull=%s "
                        + "firstCollisionCount=%d trackingSubLevelPresent=%s "
                        + "verticalCollisionBelow=%s",
                System.identityHashCode(info),
                SablePlayerCollisionCompatibility.usesCustomCollisionRepresentation(entity),
                exit,
                candidateCount,
                info.firstCollisions == null,
                count,
                info.trackingSubLevel != null,
                info.verticalCollisionBelow
        );
    }
}
