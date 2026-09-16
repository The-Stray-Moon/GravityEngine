package cc.sighs.gravityengine.event;

import cc.sighs.gravityengine.gravity.assignment.GravityAssignmentService;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.integration.OverworldGravityFieldLifecycle;
import cc.sighs.gravityengine.gravity.integration.PlayerPhysicalLoadBootstrap;
import cc.sighs.gravityengine.network.BodyAttitudeReplicationService;
import cc.sighs.gravityengine.network.GravitySyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;

public final class GravityEvents {
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity().level().isClientSide()) return;
        /* 1.21.1/21.1.249: removal -> restoreFrom/Clone -> Vanilla moveTo -> Respawn.
         * restoreFrom does not load entity NBT. Durable gravity survives BOTH death
         * and non-death replacement; only the later Respawn bootstrap derives geometry.
         */
        cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(event.getEntity())
                .gravityengine$gravityComponent().copyDurableAssignmentFrom(
                        cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(event.getOriginal())
                                .gravityengine$gravityComponent());
    }

    @SubscribeEvent
    public static void onPlayerLogin(
            PlayerEvent.PlayerLoggedInEvent ev
    ) {
        if (ev.getEntity() instanceof ServerPlayer player) {
            PlayerPhysicalLoadBootstrap
                    .bootstrapLoadedPlayer(player);
        }
    }
    @SubscribeEvent public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            EntityMovementIntegration.invalidateMovementContinuity(p);
            PlayerPhysicalLoadBootstrap.bootstrapReplacementPlayer(p);
        }
    }
    @SubscribeEvent public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            // Ordinary changeDimension revives the same ServerPlayer. Its leave seed
            // was only a detached replacement/save source, not destination actor authority.
            p.getData(cc.sighs.gravityengine.GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE)
                    .clearPendingLoadedSeed();
            /*
             * Server dimension transfer keeps the same player object and
             * always reaches an authoritative destination position write
             * (ServerGamePacketListenerImpl.teleport -> absMoveTo -> setPos).
             * That position replacement is the single gravity
             * movement-continuity owner; this event only refreshes the
             * assignment/application for the new level.
             */
            update(p);
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateAuthoritativeSuppression(p);
            GravitySyncService.syncPlayer(p);
            // Open the destination stream before its one-shot bootstrap publication.
            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeStreamEpochService.beginNewServerStream(p);
            BodyAttitudeReplicationService.syncSelf(p);
        }
    }
    @SubscribeEvent public static void onStartTracking(PlayerEvent.StartTracking ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            GravitySyncService.syncEntityToPlayer(p, ev.getTarget());
            if (ev.getTarget() instanceof Player target) {
                BodyAttitudeReplicationService.syncToPlayer(p, target);
            }
        }
    }
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof Level level) {
            OverworldGravityFieldLifecycle.register(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            /*
             * Destroy the complete generic field runtime, which is the single
             * registration authority for every field producer.
             */
            cc.sighs.gravityengine.gravity.integration.collision.CollisionObstacleRegistry
                    .remove(level);
            GravityFieldRuntime.remove(level);
        }
    }

    private static void update(Entity e) {
        var r = GravityAssignmentService.evaluate(e);
        if (r.changed()) {
            var applied = cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyFieldAssignment(
                    e,
                    r.resolved(),
                    r.fieldPresent());
            if (applied.assignmentAccepted()) {
                GravitySyncService.syncTracking(e);
            }
        }
    }
}