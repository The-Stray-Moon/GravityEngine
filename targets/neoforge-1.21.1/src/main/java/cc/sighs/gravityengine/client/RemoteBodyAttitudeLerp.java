package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeVisualConfigSnapshot;
import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeRenderSnapshot;

import cc.sighs.gravityengine.ClientConfig;
import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeInterpolation;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/** Remote network target -> entity tick approach -> partial-tick interpolation, like Vanilla lerpTo. */
public final class RemoteBodyAttitudeLerp {
    private static final Map<Player, RemoteBodyAttitudeLerp>
            STATES = new WeakHashMap<>();

    static RemoteBodyAttitudeLerp get(Player player) {
        return STATES.get(player);
    }

    static void accept(Player player, ClientboundBodyAttitudeStatePayload payload) {
        if (!player.isLocalPlayer()) {
            var state = STATES.computeIfAbsent(player, ignored -> new RemoteBodyAttitudeLerp());
            if (state.accept(payload, ClientConfig.bodyAttitudeVisual)) state.capturePresentationContract(player);
        }
    }

    static void tick(Player player) {
        var state = STATES.get(player);
        if (state != null) {
            state.capturePresentationContract(player);
            state.tick(player.tickCount);
        }
    }

    static void removeLevel(Level level) {
        STATES.keySet().removeIf(player -> player.level() == level);
    }

    public static void clearLevel() { STATES.clear(); }
    static void remove(Entity entity) { STATES.remove(entity); }

    // Vanilla entity metadata owns fall-flying. Capture its presentation intent at
    // install/entity-tick boundaries; sample() never resolves control from live entities.
    private cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract attitudeContract =
            cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.FREE_ATTITUDE;
    private void capturePresentationContract(Player player) {
        attitudeContract = player.isFallFlying()
                ? cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.ELYTRA_ALIGNED
                : cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.FREE_ATTITUDE;
    }

    private ClientboundBodyAttitudeStatePayload target;
    private Quatd previousBody;
    private Quatd currentBody;
    private Quatd previousController;
    private Quatd currentController;
    private int ticksRemaining;
    private long lastEntityTick = Long.MIN_VALUE;
    private long snapshotAgeTicks;
    private String lastSyncReason;

    boolean accept(ClientboundBodyAttitudeStatePayload next, BodyAttitudeVisualConfigSnapshot visual) {
        if (target != null && (next.streamEpoch() < target.streamEpoch()
                || next.streamEpoch() == target.streamEpoch()
                && next.authoritativeRevision() <= target.authoritativeRevision())) return false;
        boolean continuous = visible(target) && visible(next)
                && target.streamEpoch() == next.streamEpoch()
                && target.authoritativeConfigGeneration() == next.authoritativeConfigGeneration();
        boolean changed = target == null || (!target.worldFromBody().equals(next.worldFromBody())
                && Math.abs(target.worldFromBody().dot(next.worldFromBody())) < 1.0 - 1e-12)
                || Math.abs(target.worldFromController().dot(next.worldFromController())) < 1.0 - 1e-12;
        long span = target == null ? 1 : Math.max(1,
                next.authoritativeServerGameTick() - target.authoritativeServerGameTick());
        target = next;
        snapshotAgeTicks = 0;
        lastSyncReason = !continuous ? "LIFECYCLE" : changed ? "TARGET" : "HEARTBEAT";
        if (!continuous) {
            previousBody = currentBody = next.worldFromBody();
            previousController = currentController = next.worldFromController();
            ticksRemaining = 0;
        } else if (changed) {
            ticksRemaining = (int) Math.ceil(Math.clamp((double) span,
                    visual.remoteInterpolationMinTicks(), visual.remoteInterpolationMaxTicks()));
        }
        // Inactive targets end presentation immediately. Active actor state is represented
        // by active tick states; there is no private renderer release tail after INACTIVE.
        return true;
    }

    void tick(long entityTick) {
        if (entityTick == lastEntityTick) return;
        lastEntityTick = entityTick;
        if (target == null) return;
        snapshotAgeTicks++;
        previousBody = currentBody;
        previousController = currentController;
        if (ticksRemaining > 0) {
            double step = 1.0 / ticksRemaining;
            currentBody = BodyAttitudeInterpolation.shortestArc(currentBody, target.worldFromBody(), step);
            currentController = BodyAttitudeInterpolation.shortestArc(currentController, target.worldFromController(), step);
            ticksRemaining--;
        }
    }

    @Nullable BodyAttitudeRenderSnapshot sample(float partialTick) {
        if (!hasPresentation()) return null;
        var controller = new cc.sighs.gravityengine.attitude.SemanticView(
                BodyAttitudeInterpolation.shortestArc(previousController, currentController, partialTick));
        return new BodyAttitudeRenderSnapshot(
                BodyAttitudeInterpolation.shortestArc(previousBody, currentBody, partialTick),
                controller.forward(), controller, 0, 0, target.authoritativeRevision(),
                BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP,
                0, target.authoritativeRevision(), target.authoritativeServerGameTick(),
                target.streamEpoch(), target.authoritativeConfigGeneration(), attitudeContract);
    }

    boolean hasPresentation() { return visible(target); }

    private static boolean visible(ClientboundBodyAttitudeStatePayload state) {
        return state != null && state.initialized() && state.active()
                && state.continuity() == BodyAttitudeContinuity.CONTINUOUS;
    }

    DebugState debugState() {
        return new DebugState(target, ticksRemaining, snapshotAgeTicks, lastSyncReason);
    }

    record DebugState(ClientboundBodyAttitudeStatePayload current, int ticksRemaining,
                      long snapshotAgeTicks, String lastSyncReason) {}
}