package cc.sighs.gravityengine.gravity.debug;

import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import net.minecraft.world.entity.Entity;

/** Expected unsupported geometry is a bounded, visible coverage refusal, not an empty scene. */
public final class CollisionCoverageDiagnostics {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final InvariantRateLimiter REPORTS = new InvariantRateLimiter(1024, 10_000_000_000L);
    private CollisionCoverageDiagnostics() {}
    public static void report(Entity actor, RuntimeException failure) {
        String reason = failure instanceof CollisionSceneCoverageException coverage ? coverage.reason().name() : "WORK_BUDGET";
        String key = actor.getUUID() + ":" + actor.level().isClientSide() + ":" + reason;
        if (REPORTS.acquire(key, System.nanoTime())) LOGGER.warn(
                "[GravityEngine] Collision refused entity={} reason={} detail={}", actor.getUUID(), reason, failure.getMessage());
    }
}
