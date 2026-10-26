package cc.sighs.gravityengine.gravity.debug;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** JVM-only boot snapshot: safe during Mixin discovery, with no game/loader linkage.
 * Internal target instrumentation configuration, not a supported API. */
public final class BootDebugOptions {
    private static final Options OPTIONS = parse(System::getProperty, message ->
            System.getLogger("GravityEngine/Debug").log(System.Logger.Level.WARNING, message));

    private BootDebugOptions() {}

    public static DebugTraceLevel gravityLevel() { return OPTIONS.gravity(); }
    public static DebugTraceLevel movementLevel() { return OPTIONS.movement(); }
    public static DebugTraceLevel viewLevel() { return OPTIONS.view(); }
    public static boolean gravityEnabled() { return gravityLevel().enabled(); }
    public static boolean movementEnabled() { return movementLevel().enabled(); }
    public static boolean spatialStateEnabled() { return OPTIONS.spatialState(); }
    public static boolean viewEnabled() { return viewLevel().enabled(); }
    public static boolean viewStacksEnabled() { return OPTIONS.viewStacks(); }
    public static double gravityVelocityThreshold() { return OPTIONS.velocityThreshold(); }
    public static boolean failOnGravityInvariant() { return OPTIONS.failOnInvariant(); }
    public static Options options() { return OPTIONS; }

    public record Options(DebugTraceLevel gravity, DebugTraceLevel movement, boolean spatialState,
                          DebugTraceLevel view, boolean viewStacks, double velocityThreshold,
                          boolean failOnInvariant, UUID entity, DebugSide side) {
        public boolean matches(UUID candidate, boolean clientSide) {
            return side.matches(clientSide) && (entity == null || entity.equals(candidate));
        }
        public boolean anyTextEnabled() {
            return gravity.enabled() || movement.enabled() || spatialState || view.enabled();
        }
    }

    /** Pure parser used by focused tests; runtime invokes it once. Warnings omit raw user input. */
    static Options parse(Function<String, String> property, Consumer<String> warning) {
        DebugTraceLevel gravity = level(property, warning, "gravityengine.debugGravity");
        DebugTraceLevel movement = level(property, warning, "gravityengine.debugMovement");
        String spatial = property.apply("gravityengine.debugSpatialState");
        boolean spatialState = spatial == null ? gravity.enabled() || movement.enabled()
                : Boolean.parseBoolean(spatial);
        UUID entity = null;
        String configuredEntity = property.apply("gravityengine.debugEntity");
        if (configuredEntity != null) {
            try {
                entity = UUID.fromString(configuredEntity);
                if (!entity.toString().equalsIgnoreCase(configuredEntity)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                entity = null;
                warning.accept("Invalid gravityengine.debugEntity UUID; entity filtering is disabled.");
            }
        }
        DebugSide side = DebugSide.BOTH;
        String configuredSide = property.apply("gravityengine.debugSide");
        if (configuredSide != null) {
            try { side = DebugSide.valueOf(configuredSide.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) {
                warning.accept("Invalid gravityengine.debugSide; using BOTH.");
            }
        }
        double threshold = 0.05D;
        String configuredThreshold = property.apply("gravityengine.debugGravityVelocityThreshold");
        if (configuredThreshold != null) {
            try {
                double parsed = Double.parseDouble(configuredThreshold);
                if (!Double.isFinite(parsed) || parsed <= 0) throw new NumberFormatException();
                threshold = parsed;
            } catch (NumberFormatException invalid) {
                warning.accept("Invalid gravityengine.debugGravityVelocityThreshold; using 0.05.");
            }
        }
        return new Options(gravity, movement, spatialState,
                level(property, warning, "gravityengine.debugView"),
                Boolean.parseBoolean(property.apply("gravityengine.debugViewStacks")), threshold,
                Boolean.parseBoolean(property.apply("gravityengine.failOnGravityInvariant")), entity, side);
    }

    private static DebugTraceLevel level(Function<String, String> property,
                                         Consumer<String> warning, String key) {
        String value = property.apply(key);
        if (value == null) return DebugTraceLevel.OFF;
        try {
            return DebugTraceLevel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            warning.accept("Invalid " + key + "; use OFF, ANOMALY, MUTATION or FULL. Using OFF.");
            return DebugTraceLevel.OFF;
        }
    }
}
