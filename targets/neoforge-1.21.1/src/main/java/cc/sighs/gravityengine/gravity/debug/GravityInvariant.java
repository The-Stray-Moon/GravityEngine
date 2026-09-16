package cc.sighs.gravityengine.gravity.debug;

import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Non-fatal reporting channel for diagnostic gravity invariants.
 *
 * <p>A diagnostic invariant describes a state the kernel did not expect but
 * which the surrounding runtime can still resolve deterministically. Such an
 * invariant must never alter authoritative movement: enabling
 * {@code gravityengine.debugGravity} may add warnings, but it may not change
 * physics or geometry state. Throwing here would abandon an in-flight
 * GravityEngine operation, so these conditions report instead.</p>
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
            Boolean.getBoolean("gravityengine.failOnGravityInvariant");

    /** Minimum gap between reports of one invariant for one entity. */
    private static final long REPEAT_SUPPRESSION_MILLIS = 10_000L;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<String, Long> LAST_REPORT_MILLIS = new ConcurrentHashMap<>();

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
        String key = entity == null ? id : id + "#" + entity.getId();
        long now = System.currentTimeMillis();
        Long previous = LAST_REPORT_MILLIS.get(key);
        if (previous != null && now - previous < REPEAT_SUPPRESSION_MILLIS) {
            return false;
        }
        LAST_REPORT_MILLIS.put(key, now);
        return true;
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
