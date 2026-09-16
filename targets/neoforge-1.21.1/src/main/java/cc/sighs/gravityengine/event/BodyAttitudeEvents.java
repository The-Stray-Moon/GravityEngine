package cc.sighs.gravityengine.event;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionCoordinator;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Common-side lifecycle wiring for the per-player attitude component.
 *
 * <p>Server lifecycle ownership stays here; local body control runs in the
 * client-only {@code ClientBodyAttitudeLifecycle} (client loading path), so
 * this common event class never loads client runtime classes.</p>
 */
public final class BodyAttitudeEvents {
    private BodyAttitudeEvents() {}

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) {
            /*
             * Client input/continuity invalidation runs in
             * ClientBodyAttitudeLifecycle (client-only loading path).
             */
            return;
        }
        invalidateServer(player);
    }

    /**
     * Server-side leave cleanup for a player entity object.
     *
     * <p>Gravity movement continuity is deliberately not invalidated here.
     * A {@code CHANGED_DIMENSION} leave keeps the same object, which is about
     * to be repositioned by the dimension transfer's authoritative teleport;
     * that position replacement owns the single gravity discontinuity.
     * Permanently-removed player objects (logout or respawn replacement) have
     * no continuing gravity consumer, so they must not advance the
     * movement-continuity revision as generic cleanup.  Attitude continuity,
     * replication cleanup below still run for
     * every leave in their own lifecycle owners.</p>
     */
    private static void invalidateServer(Player player) {
        /* PlayerList.respawn removes before restoreFrom/Clone attachment serialization;
         * showEndCredits may remove even earlier. Capture while live, then invalidate.
         * NeoForge copies serializable attachments only for non-death Clone unless
         * copyOnDeath is enabled (deliberately absent). Logout saves before this leave.
         */
        player.getData(cc.sighs.gravityengine.GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE)
                .stageBeforeRemoval();
        cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.clear(player);
        /*
         * Lifecycle epoch is owned by the component and increments exactly once
         * per invalidation. Server/world game time is chronology for stream
         * revisions, never continuity generation.
         */
        BodyAttitudeTransactionCoordinator.invalidateContinuity(
                player,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player));
    }
}
