package cc.sighs.gravityengine.gravity.debug;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class GravityDebugLog {
    public static final boolean ENABLED = Boolean.getBoolean("gravityengine.debugGravity");
    /** Explicit opt-in: paired movement observations and diagnostic transport. */
    public static final boolean MOVEMENT_ENABLED = Boolean.getBoolean("gravityengine.debugMovement");
    /** Body-state and geometry-application diagnostics independent of the broad gravity trace. */
    public static final boolean SPATIAL_STATE_ENABLED = Boolean.parseBoolean(
            System.getProperty("gravityengine.debugSpatialState", Boolean.toString(ENABLED))) || MOVEMENT_ENABLED;
    private static final double VELOCITY_TRACE_THRESHOLD = velocityTraceThreshold();
    private static final double VERTICAL_EPSILON = 1.0E-7D;
    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private GravityDebugLog() {}

    public static void movement(Entity player, String family, String event, String fields, Object... args) {
        if (!MOVEMENT_ENABLED) return;
        try {
            LOGGER.info("[SMR-MOVE-{}] event={} uuid={} {} {}", family, event, player.getUUID(), tick(player),
                    String.format(Locale.ROOT, fields, args));
        } catch (RuntimeException loggingFailure) {
            LOGGER.debug("Movement diagnostic formatting failed", loggingFailure);
        }
    }

    public static void spatialState(Entity player, String family, String event, String fields, Object... args) {
        if (!SPATIAL_STATE_ENABLED) return;
        try {
            LOGGER.info("[SMR-{}-SPATIAL-STATE] event={} player={} uuid={} {} {}",
                    family, event, entityLabel(player), player.getUUID(), tick(player),
                    String.format(Locale.ROOT, fields, args));
        } catch (RuntimeException loggingFailure) {
            // Diagnostics never change the observed validation decision.
            LOGGER.debug("Validation log formatting failed", loggingFailure);
        }
    }

    /** Full round-trip precision: small penetration/residual differences must remain visible. */
    public static String exactVec(Vec3 value) {
        return value == null ? "unavailable" : "(" + value.x + "," + value.y + "," + value.z + ")";
    }

    public static String quaternion(org.joml.Quaterniondc value) {
        return value == null ? "unavailable"
                : "(" + value.x() + "," + value.y() + "," + value.z() + "," + value.w() + ")";
    }

    public static void log(Entity entity, String event, String message, Object... args) {
        if (!ENABLED) return;
        try {
            LOGGER.info(
                    "[GravityEngine/GravityDebug] seq={} side={} entity={} {} event={} {}",
                    TRACE_SEQUENCE.incrementAndGet(),
                    side(entity),
                    entityLabel(entity),
                    tick(entity),
                    event,
                    String.format(Locale.ROOT, message, args)
            );
        } catch (RuntimeException loggingFailure) {
            // Debug instrumentation must never interfere with movement.
            LOGGER.warn("[GravityEngine/GravityDebug] gravity debug log formatting failed", loggingFailure);
        }
    }

    public static void log(String event, String message, Object... args) {
        if (!ENABLED) return;
        try {
            LOGGER.info(
                    "[GravityEngine/GravityDebug] seq={} event={} {}",
                    TRACE_SEQUENCE.incrementAndGet(),
                    event,
                    String.format(Locale.ROOT, message, args)
            );
        } catch (RuntimeException loggingFailure) {
            LOGGER.warn("[GravityEngine/GravityDebug] gravity debug log formatting failed", loggingFailure);
        }
    }

    public static String side(Entity entity) {
        return entity.level().isClientSide() ? "C" : "S";
    }

    public static String tick(Entity entity) {
        return String.format(
                Locale.ROOT,
                "entityTick=%d gameTime=%d",
                entity.tickCount,
                entity.level().getGameTime()
        );
    }

    public static String vec(Vec3 vec) {
        if (vec == null) return "null";
        return String.format(Locale.ROOT, "(%.5f,%.5f,%.5f)", vec.x, vec.y, vec.z);
    }

    /** Neutral-vector formatting overload (physics-side helper only). */
    public static String vec(Vector3dc vec) {
        if (vec == null) return "null";
        return String.format(
                Locale.ROOT,
                "(%.5f,%.5f,%.5f)",
                vec.x(), vec.y(), vec.z());
    }

    public static String box(net.minecraft.world.phys.AABB box) {
        if (box == null) return "null";
        return String.format(
                Locale.ROOT,
                "[%.5f,%.5f,%.5f->%.5f,%.5f,%.5f]",
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ
        );
    }

    public static String formatMoveResult(GravityMoveResult result) {
        if (result == null) return "moveResultAvailable=false";
        return "moveResultAvailable=true requested="
                + vec(result.requestedMovement())
                + " resolved=" + vec(result.resolvedMovement())
                + " recovery=" + vec(result.recoveryMovement())
                + " locomotion=" + vec(result.locomotionMovement())
                + " blockedDown=" + result.blockedDown()
                + " blockedUp=" + result.blockedUp()
                + " blockedTangent=" + result.blockedTangent()
                + " grounded=" + result.gameplayGrounded()
                + " stepHeight=" + result.stepHeight()
                + " indeterminate=" + result.indeterminate()
                + " indeterminateReason=" + result.indeterminateReason()
                + " supportFollowRise=" + result.supportFollowRise()
                + " tangentBlockingNormals=" + result.tangentBlockingNormals()
                + " supportBlock="
                + result.supportBlock().map(Object::toString).orElse("none");
    }

    /**
     * World and gravity-local entity velocity for diagnostics. Uses only an
     * already-owned frame; it never samples gravity.
     */
    public static String formatEntityVelocity(Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        GravityFrame frame =
                cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                        .reliableFrame(entity);
        if (frame == null) {
            return "frameAvailable=false velocityWorld=" + vec(velocity);
        }
        return "frameAvailable=true velocityWorld=" + vec(velocity)
                + " velocityLocal=" + vec(frame.worldToLocal(velocity));
    }

    public static boolean isNotableVerticalChange(
            GravityFrame frame,
            Vec3 beforeWorld,
            Vec3 afterWorld
    ) {
        Vec3 beforeLocal = frame.worldToLocal(beforeWorld);
        Vec3 afterLocal = frame.worldToLocal(afterWorld);
        double beforeY = beforeLocal.y;
        double afterY = afterLocal.y;
        return Math.abs(afterY - beforeY) >= VELOCITY_TRACE_THRESHOLD
                || Math.abs(afterY) >= VELOCITY_TRACE_THRESHOLD
                || (beforeY <= -VELOCITY_TRACE_THRESHOLD
                && afterY >= VELOCITY_TRACE_THRESHOLD)
                || (beforeY >= VELOCITY_TRACE_THRESHOLD
                && afterY <= -VELOCITY_TRACE_THRESHOLD);
    }

    public static String verticalChangeFlags(
            Vec3 beforeLocal,
            Vec3 afterLocal
    ) {
        boolean alert = Math.abs(afterLocal.y - beforeLocal.y)
                >= VELOCITY_TRACE_THRESHOLD;
        boolean upCreated = beforeLocal.y <= VERTICAL_EPSILON
                && afterLocal.y >= VELOCITY_TRACE_THRESHOLD;
        boolean reversed = beforeLocal.y <= -VELOCITY_TRACE_THRESHOLD
                && afterLocal.y >= VELOCITY_TRACE_THRESHOLD;
        return "verticalAlert=" + alert
                + " verticalUpCreated=" + upCreated
                + " verticalDirectionReversed=" + reversed;
    }

    public static String callerStack() {
        if (!ENABLED) return "disabled";
        return WalkerHolder.WALKER.walk(frames -> frames
                .filter(frame -> meaningfulCaller(frame.getClassName()))
                .limit(8)
                .map(frame -> frame.getClassName() + "."
                        + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(Collectors.joining(" > "))
        );
    }

    private static boolean meaningfulCaller(String className) {
        return !className.startsWith(GravityDebugLog.class.getName())
                && !className.equals(Thread.class.getName())
                && !className.startsWith("java.lang.StackWalker");
    }

    private static double velocityTraceThreshold() {
        String configured = System.getProperty(
                "gravityengine.debugGravityVelocityThreshold", "0.05"
        );
        try {
            double parsed = Double.parseDouble(configured);
            return Double.isFinite(parsed) && parsed > 0.0D ? parsed : 0.05D;
        } catch (NumberFormatException ignored) {
            return 0.05D;
        }
    }

    private static String entityLabel(Entity entity) {
        return entity.getName().getString().replace(' ', '_')
                + "/" + entity.getId();
    }

    private static final class WalkerHolder {
        private static final StackWalker WALKER = StackWalker.getInstance();
    }

}