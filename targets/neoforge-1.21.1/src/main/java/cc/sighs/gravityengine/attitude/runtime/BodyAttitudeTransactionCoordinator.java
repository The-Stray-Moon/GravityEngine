package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/** Publishes actor state and semantic look. Character geometry, support and translation have separate owners. */
public final class BodyAttitudeTransactionCoordinator {
    private BodyAttitudeTransactionCoordinator() {}

    /** Explicit server look command handoff. Position-only reconciliation never
     * calls this seam. Body attitude, momentum and logical-step ownership survive. */
    public static void acceptExplicitLook(Player player) {
        var component = BodyAttitudeRuntime.Access.component(player);
        synchronized (component) {
            var before = component.snapshot();
            if (before.ownership() != BodyAttitudeOwnership.ACTIVE || !before.view().initialized()) return;
            var frame = GravityInfluencePolicy.usesGravityLocalLook(player)
                    ? GravityFrameAccess.authoritativeFrame(player) : GravityFrame.DEFAULT;
            var controller = GravityLocalLook.lookQuaternion(
                    frame, player.getYRot(), player.getXRot(), 0);
            component.commitViewOnly(BodyRelativeViewState.fromSemantic(
                    new SemanticView(controller),
                    before.state().currentWorldFromBody(), before.view().localLook()), before.lastLocalSimulationStep());
        }
    }

    /** Project the retiring semantic authority once, before a scalar look owner
     * is allowed to interpret relative rotation. Never infer look from stale carriers. */
    public static void projectActiveLook(Player player) {
        var component = BodyAttitudeRuntime.Access.component(player);
        synchronized (component) {
            var before = component.snapshot();
            if (before.ownership() != BodyAttitudeOwnership.ACTIVE || !before.view().initialized()) return;
            var frame = GravityInfluencePolicy.usesGravityLocalLook(player)
                    ? GravityFrameAccess.authoritativeFrame(player) : GravityFrame.DEFAULT;
            var look = AttitudeSpaceTransform.releaseLookRebase(
                    frame, before.state().currentWorldFromBody(), before.view(), player.getYRot(), player.getXRot());
            applyLookRebase(player, new BodyAttitudeLookRebase(look.sourceYaw(), look.sourcePitch()));
        }
    }
    public static boolean commitLogicalOnly(
            Player player,
            BodyAttitudeComponent component,
            BodyAttitudeLogicalCandidate logical
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(logical, "logical");

        synchronized (component) {
            BodyAttitudeTransactionPrecondition expected =
                    logical.precondition();

            if (component.snapshot() != expected.component()) {
                return false;
            }

            BodyAttitudeComponent.Snapshot oldComponent =
                    component.snapshot();

            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            float oldYawPrevious = player.yRotO;
            float oldPitchPrevious = player.xRotO;

            try {
                applyLookRebase(player, logical.lookRebase());
                component.commitLogicalCandidate(logical);

                if (PlayerViewDebugLog.shouldLog(player)) {
                    PlayerViewDebugLog.mutation(
                            player,
                            "attitude-logical-commit/" + logical.kind(),
                            oldComponent
                    );
                }

                return true;
            } catch (RuntimeException | Error failure) {
                try {
                    component.restoreSnapshot(oldComponent);
                    try {
                        player.setYRot(oldYaw);
                    } finally {
                        try {
                            player.setXRot(oldPitch);
                        } finally {
                            player.yRotO = oldYawPrevious;
                            player.xRotO = oldPitchPrevious;
                        }
                    }
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }


    /**
     * Lifecycle continuity invalidation (level leave, dimension transfer,
     * respawn continuity reset, disconnect). Resets continuity, ownership/decision and the last observed Vanilla tick as one
     * owner publication. The caller still owns transport cleanup (body
     * replication caches); accepted component authority is published
     * here only.
     */
    public static void invalidateContinuity(
            Player player,
            BodyAttitudeComponent component
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(component, "component");
        synchronized (component) {
            var before = PlayerViewDebugLog.shouldLog(player) ? component.snapshot() : null;
            cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
            component.invalidateContinuity();
            PlayerViewDebugLog.mutation(player, "attitude-invalidate", before);
        }
    }

    /**
     * Retires an authoritative stream watermark into a replacement player
     * instance (client respawn clone). The retired epoch stays closed; only a
     * strictly newer server stream can establish the replacement baseline.
     */
    public static void retireAuthoritativeStream(
            Player player,
            BodyAttitudeComponent component,
            long retiredStreamEpoch
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(component, "component");
        synchronized (component) {
            var before = PlayerViewDebugLog.shouldLog(player) ? component.snapshot() : null;
            cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
            component.retireAuthoritativeStream(retiredStreamEpoch);
            PlayerViewDebugLog.mutation(player, "attitude-retire-stream", before);
        }
    }

    /**
     * Opens a new authoritative stream epoch on the server. The epoch value
     * is allocated by the transport service; the accepted component stream
     * publication happens through this owning coordinator.
     */
    public static void openAuthoritativeStream(
            Player player,
            BodyAttitudeComponent component,
            long streamEpoch
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(component, "component");
        synchronized (component) {
            // Chronology only. Lifecycle/reset owners clear mode and actor together.
            // ensureServerStream must not turn a valid active swim into mode=false.
            component.beginAuthoritativeStream(streamEpoch);
            if (PlayerViewDebugLog.shouldLog(player)) {
                PlayerViewDebugLog.event(player, "attitude-open-stream", "stream=%s", streamEpoch);
            }
        }
    }

    public static void publishCurrentAuthoritativeState(
            Player player,
            long serverGameTick,
            long configGeneration
    ) {
        Objects.requireNonNull(player, "player");

        BodyAttitudeComponent component =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);

        synchronized (component) {
            component.publishCurrentAuthoritativeState(
                    serverGameTick,
                    configGeneration
            );
        }
    }

    private static void applyLookRebase(
            Player player,
            BodyAttitudeLookRebase rebase
    ) {
        if (rebase == null) {
            return;
        }

        try (var trace = PlayerViewDebugLog.begin(
                player,
                "ownership-look-projection"
        )) {
            if (PlayerViewDebugLog.shouldLog(player)) {
                PlayerViewDebugLog.event(
                        player,
                        "ownership-look-target",
                        "yaw=%s pitch=%s",
                        rebase.sourceYaw(),
                        rebase.sourcePitch()
                );
            }

            /*
             * Ownership release changes the representation carried by the
             * Vanilla yaw/pitch scalars.
             *
             * yRotO/xRotO therefore MUST cross the representation boundary
             * atomically with yRot/xRot.  Leaving the old values untouched would
             * make getViewYRot(partialTick)/getViewXRot(partialTick) interpolate
             * between two different coordinate representations for one render
             * interval.
             *
             * This is not smoothing.  It explicitly prevents an invalid
             * interpolation across the ownership boundary.
             */
            player.setYRot(rebase.sourceYaw());
            player.setXRot(rebase.sourcePitch());

            player.yRotO = rebase.sourceYaw();
            player.xRotO = rebase.sourcePitch();
        }
    }

}
