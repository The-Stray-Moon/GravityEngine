package cc.sighs.gravityengine.gravity.debug;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class GravityDebugLog {
    private static final DebugTraceLevel GRAVITY = BootDebugOptions.gravityLevel();
    private static final DebugTraceLevel MOVEMENT = BootDebugOptions.movementLevel();
    private static final boolean SPATIAL = BootDebugOptions.spatialStateEnabled();
    private static final double VELOCITY_TRACE_THRESHOLD = BootDebugOptions.gravityVelocityThreshold();

    public static boolean shouldLog(Entity entity) {
        return GRAVITY.enabled() && DebugFilter.matches(entity);
    }
    public static boolean shouldLogMovement(Entity entity) {
        return MOVEMENT.enabled() && DebugFilter.matches(entity);
    }
    public static boolean shouldLogSpatialState(Entity entity) {
        return SPATIAL && DebugFilter.matches(entity);
    }
    private static final double VERTICAL_EPSILON = 1.0E-7D;
    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private GravityDebugLog() {}

    public static void movement(Entity player, String family, String event, String fields, Object... args) {
        if (!shouldLogMovement(player)) return;
        try {
            String detail = String.format(Locale.ROOT, fields, args);
            if (event.equals("collision-kernel") || event.equals("move-input")) {
                DebugLogPolicy.trace(LOGGER, "[SMR-MOVE-{}] event={} uuid={} {} {}",
                        family, event, player.getUUID(), tick(player), detail);
            } else {
                DebugLogPolicy.summary(LOGGER, "[SMR-MOVE-{}] event={} uuid={} {} {}",
                        family, event, player.getUUID(), tick(player), detail);
            }
        } catch (RuntimeException loggingFailure) {
            LOGGER.debug("Movement diagnostic formatting failed", loggingFailure);
        }
    }

    public static void spatialState(Entity player, String family, String event, String fields, Object... args) {
        if (!shouldLogSpatialState(player)) return;
        try {
            DebugLogPolicy.summary(LOGGER, "[SMR-{}-SPATIAL-STATE] event={} player={} uuid={} {} {}",
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

    /** Full round-trip precision for GravityEngine-owned values. */
    public static String exactVec(Vec3d value) {
        return value == null
                ? "unavailable"
                : "(" + value.x() + "," + value.y() + "," + value.z() + ")";
    }

    public static String quaternion(Quatd value) {
        return value == null ? "unavailable"
                : "(" + value.x() + "," + value.y() + "," + value.z() + "," + value.w() + ")";
    }

    public static void log(Entity entity, String event, String message, Object... args) {
        if (!shouldLog(entity)) return;
        try {
            String pattern = "[GravityEngine/GravityDebug] seq={} side={} entity={} {} event={} {}";
            Object[] values = {TRACE_SEQUENCE.incrementAndGet(), side(entity), entityLabel(entity), tick(entity),
                    event, String.format(Locale.ROOT, message, args)};
            switch (event) {
                case "body-transition", "body-transition-publish", "body-transition-relocate",
                        "body-commit-applied", "body-commit-deferred" ->
                        LOGGER.info(pattern, values);
                case "client-move-out", "client-packet-send", "geometry-frame", "move-resolved", "velocity-response-fallback" ->
                        DebugLogPolicy.summary(LOGGER, pattern, values);
                default -> DebugLogPolicy.trace(LOGGER, pattern, values);
            }
        } catch (RuntimeException loggingFailure) {
            // Debug instrumentation must never interfere with movement.
            LOGGER.debug("[GravityEngine/GravityDebug] gravity debug log formatting failed", loggingFailure);
        }
    }

    public static void log(String event, String message, Object... args) {
        if (!shouldLog(null)) return;
        try {
            DebugLogPolicy.trace(LOGGER,
                    "[GravityEngine/GravityDebug] seq={} event={} {}",
                    TRACE_SEQUENCE.incrementAndGet(),
                    event,
                    String.format(Locale.ROOT, message, args)
            );
        } catch (RuntimeException loggingFailure) {
            LOGGER.debug("[GravityEngine/GravityDebug] gravity debug log formatting failed", loggingFailure);
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

    /** Neutral-vector formatting overload for GravityEngine-owned values. */
    public static String vec(Vec3d vec) {
        if (vec == null) {
            return "null";
        }
        return String.format(
                Locale.ROOT,
                "(%.5f,%.5f,%.5f)",
                vec.x(),
                vec.y(),
                vec.z()
        );
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
                + " velocityLocal=" + vec(frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(velocity)));
    }

    public static boolean isNotableVerticalChange(
            GravityFrame frame,
            Vec3 beforeWorld,
            Vec3 afterWorld
    ) {
        Vec3d beforeLocal = frame.worldToLocal(
                MinecraftMathAdapter.toVec3d(beforeWorld));
        Vec3d afterLocal = frame.worldToLocal(
                MinecraftMathAdapter.toVec3d(afterWorld));
        double beforeY = beforeLocal.y();
        double afterY = afterLocal.y();
        return Math.abs(afterY - beforeY) >= VELOCITY_TRACE_THRESHOLD
                || Math.abs(afterY) >= VELOCITY_TRACE_THRESHOLD
                || (beforeY <= -VELOCITY_TRACE_THRESHOLD
                && afterY >= VELOCITY_TRACE_THRESHOLD)
                || (beforeY >= VELOCITY_TRACE_THRESHOLD
                && afterY <= -VELOCITY_TRACE_THRESHOLD);
    }

    public static String verticalChangeFlags(
            Vec3d beforeLocal,
            Vec3d afterLocal
    ) {
        boolean alert = Math.abs(afterLocal.y() - beforeLocal.y())
                >= VELOCITY_TRACE_THRESHOLD;
        boolean upCreated = beforeLocal.y() <= VERTICAL_EPSILON
                && afterLocal.y() >= VELOCITY_TRACE_THRESHOLD;
        boolean reversed = beforeLocal.y() <= -VELOCITY_TRACE_THRESHOLD
                && afterLocal.y() >= VELOCITY_TRACE_THRESHOLD;
        return "verticalAlert=" + alert
                + " verticalUpCreated=" + upCreated
                + " verticalDirectionReversed=" + reversed;
    }

    public static String callerStack() {
        if (!BootDebugOptions.viewStacksEnabled()) return "disabled";
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

    private static String entityLabel(Entity entity) {
        return entity.getName().getString().replace(' ', '_')
                + "/" + entity.getId();
    }

    private static final class WalkerHolder {
        private static final StackWalker WALKER = StackWalker.getInstance();
    }

}
