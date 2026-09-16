package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.GravityEngineAttachments;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeStreamEpochService;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.BodyAttitudeReplicationService;
import cc.sighs.gravityengine.network.GravitySyncService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Single high-level owner for the server player physical load bootstrap.
 *
 * <p>Ordering contract: (1) refresh canonical load inputs (FIELD
 * assignment/presence + authoritative suppression) without publishing any
 * derived application; (2) perform exactly one derived application/geometry
 * reconstruction; (3) restore any pending BodyAttitude persistence seed or
 * bootstrap the displayed actor baseline without movement ownership; (4) only after the accepted physical commit
 * ensure a fresh authoritative stream and publish normal synchronization.
 * The retry boundary stays alive (flag kept set) until the whole transaction
 * succeeds, and retries re-enter exactly this owner. No independent event
 * subscriber may race this owner.</p>
 */
public final class PlayerPhysicalLoadBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerPhysicalLoadBootstrap() {}

    public static boolean bootstrapLoadedPlayer(
            ServerPlayer player
    ) {
        Objects.requireNonNull(player, "player");

        /*
         * Force creation of the persistence attachment so future player.dat
         * saves always have a persistence slot even for a newly-created
         * player.
         */
        player.getData(
                GravityEngineAttachments
                        .BODY_ATTITUDE_PERSISTENCE
        );

        var component =
                GravityEntityAccess.cast(player)
                        .gravityengine$gravityComponent();

        /*
         * Keep the retry boundary alive until the complete player physical load
         * transaction succeeds, not merely until gravity application succeeds.
         */
        component.markApplicationBootstrapPending();

        /*
         * Load-time input reconstruction is state-only:
         * FIELD assignment/presence + current authoritative suppression.
         */
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.refreshLoadedInputs(
                player
        );

        /*
         * Exactly one derived application/geometry reconstruction.
         */
        var application =
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                        .bootstrapLoadedApplication(
                                player
                        );

        if (application.status()
                != cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                        .TransitionStatus.COMMITTED
                && application.status()
                != cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                        .TransitionStatus.UNCHANGED) {
            return false;
        }

        /*
         * Consume a persistence seed if present; otherwise, when BodyAttitude
         * ownership is currently ACTIVE, synchronously install the legal displayed
         * baseline. Actor restore has no collision legality or position authority.
         */
        cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Persistence.Result attitude =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Persistence
                        .restoreOrBootstrap(
                                player
                        );

        if (attitude
                == cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Persistence
                        .Result.FAILED) {
            return false;
        }

        /*
         * A disk/replacement load starts new network chronology even if an older
         * stream accidentally remains open. This must preserve the actor state that
         * was just physically accepted; the continuity-invalidating stream variant
         * is never used here.
         */
        try {
            BodyAttitudeStreamEpochService
                    .beginFreshServerStreamPreservingContinuity(player);
            GravitySyncService.syncPlayer(player);
            BodyAttitudeReplicationService.syncSelf(player);
        } catch (RuntimeException failure) {
            /*
             * The load owner stays pending. A later base tick retries after current
             * policy revalidation; accepted physical state remains committed and
             * retry may allocate another fresh stream epoch.
             */
            LOGGER.error(
                    "Failed to finish GravityEngine physical load bootstrap for {}",
                    player.getGameProfile().getName(),
                    failure);
            return false;
        }

        /*
         * The retry flag belongs to the whole bootstrap transaction, including first
         * synchronization. If either synchronization throws, the next retry
         * re-enters the same high-level owner.
         */
        component.clearApplicationBootstrapPending();

        return true;
    }

    /**
     * Replacement-player variant used by respawn and non-death clone flows.
     * The Clone event has transferred durable gravity; NeoForge copies the staged
     * attitude attachment only for non-death replacement; the same single bootstrap owner
     * rebuilds application, consumes any copied seed and only then starts the
     * fresh server stream.
     */
    public static boolean bootstrapReplacementPlayer(
            ServerPlayer player
    ) {
        return bootstrapLoadedPlayer(player);
    }
}
