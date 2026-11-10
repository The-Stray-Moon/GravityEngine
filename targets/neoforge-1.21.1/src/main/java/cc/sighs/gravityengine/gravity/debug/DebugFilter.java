package cc.sighs.gravityengine.gravity.debug;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Immutable boot selection adapted to logical game objects. No client linkage. */
public final class DebugFilter {
    private static final BootDebugOptions.Options OPTIONS = BootDebugOptions.options();
    private DebugFilter() {}

    public static boolean matches(Entity entity) {
        // Unattributed events must not leak around a configured entity/side filter.
        if (entity == null) return OPTIONS.entity() == null && OPTIONS.side() == DebugSide.BOTH;
        return matches(OPTIONS.entity() == null ? null : entity.getUUID(), entity.level());
    }

    public static boolean matches(Level level) {
        return level == null ? OPTIONS.side() == DebugSide.BOTH : OPTIONS.side().matches(level.isClientSide());
    }

    public static boolean matches(java.util.UUID entityId, Level level) {
        if (level == null) return OPTIONS.side() == DebugSide.BOTH
                && (OPTIONS.entity() == null || OPTIONS.entity().equals(entityId));
        return OPTIONS.matches(entityId, level.isClientSide());
    }
}
