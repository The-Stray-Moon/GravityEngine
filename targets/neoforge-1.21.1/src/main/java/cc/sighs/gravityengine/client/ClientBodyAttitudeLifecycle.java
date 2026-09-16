package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;

/**
 * Client disconnect/lifecycle invalidation without retaining players in a
 * global cache.  This class is the client-only loading path for local
 * body-attitude input capture, local body control and client input cleanup.
 */
public final class ClientBodyAttitudeLifecycle {
    private ClientBodyAttitudeLifecycle() {}
    private static ClientLevel lastLevel;

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            BodyAttitudeTransactionCoordinator.invalidateContinuity(
                    player, cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player));
            ClientBodyAttitudeControl.clear(player);
        }
        ClientBodyAttitudeSync.clearConnection();
        RemoteBodyAttitudeLerp.clearLevel();
        ClientCharacterPresentation.clear();
        lastLevel = null;
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        ClientBodyAttitudeControl.observeCaptureAvailability();
        ClientLevel current = Minecraft.getInstance().level;
        if (lastLevel != current) {
            // Login/dimension packets may arrive before the first client level tick.
            // Keep latest-per-entity bootstrap states until readiness/expiry; UUID and
            // stream validation prevent application to replaced players.
            ClientBodyAttitudeControl.clearLevel();
            if (lastLevel != null) {
                RemoteBodyAttitudeLerp.removeLevel(lastLevel);
            }
            ClientCharacterPresentation.clear();
            lastLevel = current;
        }
        ClientBodyAttitudeSync.tick();
        if (current != null) for (Player player : current.players()) ClientCharacterPresentation.tick(player);
    }

    /** Copy local Q history once per entity tick, even when this tick cannot commit a body step. */
    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide() || !player.isLocalPlayer()) return;
        var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);
        if (component != null) component.beginBodyTick();
    }

    /** Body control follows the one ordinary LocalPlayer movement tick. */
    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide()) return;
        if (player.isLocalPlayer()) ClientBodyAttitudeControl.endTick(player);
        else RemoteBodyAttitudeLerp.tick(player);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientBodyAttitudeSync.onEntityJoin(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        RemoteBodyAttitudeLerp.remove(event.getEntity());

        if (event.getEntity() instanceof Player player) {
            /*
             * Entity leave owns object-local cleanup only.
             *
             * A leaving player object may be terminal (disconnect / replacement /
             * tracking removal).  Advancing semantic gravity/body continuity on an
             * object that no longer has a continuing consumer is meaningless.
             *
             * A continuing dimension transition is classified separately by
             * PlayerChangedDimensionEvent below.
             */
            cleanupClientPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangeDimension(
            PlayerEvent.PlayerChangedDimensionEvent event
    ) {
        Player player = event.getEntity();

        if (!player.level().isClientSide()) {
            return;
        }

        /*
         * This is the client-side semantic continuity boundary for a continuing
         * player dimension stream.
         */
        invalidateClientContinuity(player);
    }

    private static void cleanupClientPlayer(Player player) {
        ClientBodyAttitudeSync.remove(player);
        RemoteBodyAttitudeLerp.remove(player);
        ClientCharacterPresentation.remove(player);
        ClientBodyAttitudeControl.clear(player);
        cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.clear(player);
    }

    private static void invalidateClientContinuity(Player player) {
        cleanupClientPlayer(player);

        EntityMovementIntegration.invalidateMovementContinuity(player);

        /*
         * The component owns lifecycle-epoch allocation. World chronology must
         * never masquerade as lifecycle generation: repeated, paused or even
         * rolled-back client level time does not define how many continuity
         * invalidations have occurred.
         */
        BodyAttitudeTransactionCoordinator.invalidateContinuity(
                player,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player));
    }

    /**
     * Client respawn: the replaced local player object is retired and its
     * client input/look state is cleared before the replacement stream epoch
     * is retired on the new player.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player replacement = event.getEntity();
        if (!replacement.level().isClientSide()) return;
        Player original = event.getOriginal();
        cleanupClientPlayer(original);
        long retiredStreamEpoch = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access
                .component(original).snapshot()
                .authoritativeStreamEpoch();
        BodyAttitudeTransactionCoordinator.retireAuthoritativeStream(
                replacement,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(replacement),
                retiredStreamEpoch);
    }
}