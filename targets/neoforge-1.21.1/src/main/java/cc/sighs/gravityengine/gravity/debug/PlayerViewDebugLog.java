package cc.sighs.gravityengine.gravity.debug;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Opt-in view-write attribution. Reads existing actor state only: no component
 * creation, field sampling, semantic input resolution or presentation sampling. */
public final class PlayerViewDebugLog {
    private static final DebugTraceLevel LEVEL = BootDebugOptions.viewLevel();
    private static final boolean STACKS = BootDebugOptions.viewStacksEnabled();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private PlayerViewDebugLog() {}

    public static boolean shouldLog(Entity entity) {
        return LEVEL.enabled() && DebugFilter.matches(entity);
    }

    public static boolean shouldLog(java.util.UUID entityId, net.minecraft.world.level.Level level) {
        return LEVEL.enabled() && DebugFilter.matches(entityId, level);
    }

    /** Packet may precede entity creation. Filter the packet's actual identity and owning Level. */
    public static void packet(java.util.UUID entityId, net.minecraft.world.level.Level level,
            String event, String fields, Object... args) {
        if (!shouldLog(entityId, level)) return;
        try {
            DebugLogPolicy.summary(LOGGER, "[SMR-VIEW] event={} uuid={} side={} {}",
                    event, entityId, level == null ? "unavailable" : level.isClientSide() ? "C" : "S",
                    String.format(Locale.ROOT, fields, args));
        } catch (RuntimeException failure) {
            LOGGER.debug("Packet view diagnostic unavailable", failure);
        }
    }

    public static boolean playerEnabled(Entity entity) {
        return shouldLog(entity) && entity instanceof Player;
    }

    public static void event(Entity entity, String event, String fields, Object... args) {
        if (!shouldLog(entity)) return;
        emit(entity, event, 0, "event", fields, args);
    }

    public static Scope begin(Entity entity, String event) {
        return shouldLog(entity) ? begin(entity, event, "") : Scope.NOOP;
    }

    public static void mutation(Entity entity, String event, BodyAttitudeComponent.Snapshot before) {
        if (!shouldLog(entity)) return;
        try {
            event(entity, event, "beforeAttitude={%s}", attitude(before));
        } catch (RuntimeException failure) {
            LOGGER.debug("View mutation diagnostic unavailable", failure);
        }
    }

    /** Invocation-local pairing, including exceptional exits. No ThreadLocal or
     * entity-owned trace history is introduced. Callers pass immutable operands. */
    public static Scope begin(Entity entity, String event, String fields, Object... args) {
        if (!shouldLog(entity)) return Scope.NOOP;
        long span = SEQUENCE.incrementAndGet();
        emit(entity, event, span, "before", fields, args);
        return new Scope(entity, event, span);
    }

    private static void emit(Entity entity, String event, long span, String phase,
                             String fields, Object... args) {
        try {
            String message = "[SMR-VIEW] seq={} span={} phase={} event={} thread={} actor={} {} state={} caller={}";
            Object[] values = {SEQUENCE.incrementAndGet(), span, phase, event, Thread.currentThread().getName(),
                    identity(entity), String.format(Locale.ROOT, fields, args), state(entity), caller()};
            if (event.startsWith("attitude-logical-commit/") || event.equals("attitude-invalidate")
                    || event.equals("attitude-retire-stream") || event.equals("attitude-open-stream")
                    || event.equals("client-position-packet") || event.equals("client-attitude-send")) {
                DebugLogPolicy.summary(LOGGER, message, values);
            } else {
                DebugLogPolicy.trace(LOGGER, message, values);
            }
        } catch (RuntimeException failure) {
            // An unavailable construction/lifecycle sample must not affect gameplay.
            LOGGER.debug("View diagnostic unavailable", failure);
        }
    }

    private static String identity(Entity entity) {
        if (entity == null) return "none";
        return "id=" + entity.getId() + ",uuid=" + entity.getUUID()
                + ",instance=" + Integer.toHexString(System.identityHashCode(entity))
                + ",side=" + (entity.level() == null ? "unavailable" : entity.level().isClientSide() ? "C" : "S")
                + ",dimension=" + (entity.level() == null ? "unavailable" : entity.level().dimension().location())
                + ",entityTick=" + entity.tickCount
                + ",gameTime=" + (entity.level() == null ? "unavailable" : entity.level().getGameTime());
    }

    static String state(Entity entity) {
        if (entity == null) return "unavailable";
        String scalars = "P=" + GravityDebugLog.exactVec(entity.position())
                + ",yaw=" + entity.getYRot() + ",pitch=" + entity.getXRot()
                + ",yawOld=" + entity.yRotO + ",pitchOld=" + entity.xRotO;
        if (!(entity instanceof Player player) || !(player instanceof cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access access)) return scalars;
        BodyAttitudeComponent component = access.gravityengine$peekBodyAttitude();
        return scalars + ",bodyYaw=" + player.yBodyRot + ",bodyYawOld=" + player.yBodyRotO
                + ",headYaw=" + player.yHeadRot + ",headYawOld=" + player.yHeadRotO
                + ",component=" + (component == null ? "absent"
                : Integer.toHexString(System.identityHashCode(component)) + "," + attitude(component.snapshot()));
    }

    /** Snapshot formatter also usable for proposed/rejected installs. */
    public static String attitude(BodyAttitudeComponent.Snapshot snapshot) {
        var body = snapshot.state();
        var view = snapshot.view();
        return "ownership=" + snapshot.ownership() + ",continuity=" + snapshot.continuity()
                + ",decision=" + snapshot.decision() + ",initialized=" + body.initialized()
                + ",q=" + GravityDebugLog.quaternion(body.currentWorldFromBody())
                + ",qOld=" + GravityDebugLog.quaternion(body.previousWorldFromBody())
                + ",omega=" + GravityDebugLog.exactVec(body.angularVelocityWorld())
                + ",view=" + view.localLook() + ",viewInitialized=" + view.initialized()
                + ",worldForward=" + (body.initialized() && view.initialized()
                ? GravityDebugLog.exactVec(view.semantic(body.currentWorldFromBody()).forward()) : "unavailable")
                + ",revision=" + body.revision() + ",localStep=" + snapshot.lastLocalSimulationStep()
                + ",lifecycle=" + snapshot.lifecycleEpoch() + ",stream=" + snapshot.authoritativeStreamEpoch()
                + ",authoritativeRevision=" + snapshot.authoritativeRevision()
                + ",serverTick=" + snapshot.authoritativeServerGameTick()
                + ",config=" + snapshot.authoritativeConfigGeneration();
    }

    private static String caller() {
        if (!STACKS) return "disabled";
        return WalkerHolder.WALKER.walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(PlayerViewDebugLog.class.getName())
                        && !frame.getClassName().startsWith(PlayerViewDebugLog.class.getName() + "$"))
                .limit(8)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(Collectors.joining(" <- ")));
    }

    private static final class WalkerHolder {
        private static final StackWalker WALKER = StackWalker.getInstance();
    }

    public static final class Scope implements AutoCloseable {
        public static final Scope NOOP = new Scope(null, "", 0);
        private final Entity entity;
        private final String event;
        private final long span;
        private Scope(Entity entity, String event, long span) {
            this.entity = entity;
            this.event = event;
            this.span = span;
        }
        @Override public void close() {
            if (span != 0) emit(entity, event, span, "after", "");
        }
    }
}
