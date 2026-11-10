package cc.sighs.gravityengine.gravity.debug;

import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Correctness telemetry, not debug tracing. Independent of debug domains and filters.
 *
 * <p>A diagnostic invariant describes a state the kernel did not expect but
 * which the surrounding runtime can still resolve deterministically. Such an
 * invariant reports a warning even with all debug flags disabled. Only the
 * explicit fail-on-invariant test mode throws and abandons the operation.</p>
 *
 * <p>Genuine kernel contracts whose violation would corrupt state stay fatal in
 * their own call sites, independently of any debug flag.</p>
 *
 * <p>Diagnostics are constructed lazily so hot paths pay no formatting cost
 * while an invariant holds, and repeats are rate limited per invariant per
 * entity to honour the no-per-tick-spam rule.</p>
 */
public final class GravityInvariant {
    /**
     * Opt-in fatal mode for tests that assert an invariant is detected. Never
     * enabled by ordinary client or server runs.
     */
    public static final boolean THROW_ON_VIOLATION =
            BootDebugOptions.failOnGravityInvariant();

    /** Minimum gap between reports of one invariant for one entity. */
    private static final long REPEAT_SUPPRESSION_MILLIS = 10_000L;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final InvariantRateLimiter REPORTS = new InvariantRateLimiter(
            1024, REPEAT_SUPPRESSION_MILLIS * 1_000_000L);

    private GravityInvariant() {}

    /**
     * Reports {@code id} when {@code violated}, without altering control flow.
     *
     * @param diagnostic evaluated only when a report is actually emitted
     */
    public static void report(String id, boolean violated, Supplier<String> diagnostic) {
        report(null, id, violated, diagnostic);
    }

    /**
     * Reports {@code id} against one entity when {@code violated}, without
     * altering control flow.
     *
     * @param diagnostic evaluated only when a report is actually emitted
     */
    public static void report(
            @Nullable Entity entity,
            String id,
            boolean violated,
            Supplier<String> diagnostic
    ) {
        if (!violated) return;
        if (THROW_ON_VIOLATION) {
            throw new IllegalStateException(id + ": " + diagnostic.get());
        }
        if (!shouldReport(id, entity)) return;
        LOGGER.warn(
                "[GravityEngine/GravityInvariant] {} {} {}",
                id,
                entity == null ? "" : label(entity),
                diagnostic.get()
        );
    }

    private static boolean shouldReport(String id, @Nullable Entity entity) {
        String key = entity == null ? id : id + "#" + entity.getUUID()
                + "#" + entity.level().isClientSide();
        return REPORTS.acquire(key, System.nanoTime());
    }

    private static String label(Entity entity) {
        return String.format(
                "[%s id=%d tick=%d]",
                entity.level().isClientSide() ? "client" : "server",
                entity.getId(),
                entity.tickCount
        );
    }
}
