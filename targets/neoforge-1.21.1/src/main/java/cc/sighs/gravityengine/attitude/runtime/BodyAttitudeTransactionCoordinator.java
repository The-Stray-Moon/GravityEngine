package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import net.minecraft.world.entity.player.Player;
import java.util.Objects;

/** Publishes actor state and semantic look. Character geometry, support and translation have separate owners. */
public final class BodyAttitudeTransactionCoordinator {
    private BodyAttitudeTransactionCoordinator() {}
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

                if (PlayerViewDebugLog.ENABLED) {
                    PlayerViewDebugLog.mutation(
                            player,
                            "attitude-logical-commit/" + logical.kind(),
                            oldComponent
                    );
                }

                return true;
            } catch (RuntimeException failure) {
                if (PlayerViewDebugLog.ENABLED) {
                    PlayerViewDebugLog.event(
                            player,
                            "attitude-logical-rollback-before",
                            "failure=%s",
                            failure.toString()
                    );
                }

                component.restoreSnapshot(oldComponent);

                player.setYRot(oldYaw);
                player.setXRot(oldPitch);
                player.yRotO = oldYawPrevious;
                player.xRotO = oldPitchPrevious;

                if (PlayerViewDebugLog.ENABLED) {
                    PlayerViewDebugLog.event(
                            player,
                            "attitude-logical-rollback-after",
                            ""
                    );
                }

                return false;
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
            var before = PlayerViewDebugLog.ENABLED ? component.snapshot() : null;
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
            var before = PlayerViewDebugLog.ENABLED ? component.snapshot() : null;
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
            if (PlayerViewDebugLog.ENABLED) {
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
            if (PlayerViewDebugLog.ENABLED) {
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
