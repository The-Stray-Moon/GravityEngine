package cc.sighs.gravityengine.gravity.integration.geometry;


import cc.sighs.gravityengine.gravity.integration.PlayerPhysicalLoadBootstrap;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;

/** Only the connection tick's completed native position boundary may change a player's body mode. */
public final class PlayerBodyHandoff {
    private static final ThreadLocal<ServerPlayer> AUTHORITY = new ThreadLocal<>();
    private static final java.util.Map<ServerPlayer, Integer> LAST_RESYNC_TICK =
            new java.util.WeakHashMap<>();
    private static final java.util.Set<ServerPlayer> RESYNC_PENDING =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private PlayerBodyHandoff() {}

    /** MAIN-thread, sender-only, rate limited. Never accepts a client position. */
    public static void requestResync(ServerPlayer player) {
        int now = player.tickCount;
        Integer last = LAST_RESYNC_TICK.get(player);
        if (last != null && now >= last && now - last < 20) return;
        LAST_RESYNC_TICK.put(player, now);
        RESYNC_PENDING.add(player);
    }

    public static boolean mayChangeBody(Entity entity) {
        if (!(entity instanceof Player)) return true;
        // Client geometry is installed only by the committed server payload,
        // never by the desired-application planner or a local recovery query.
        if (entity.level().isClientSide()) return false;
        if (!(entity instanceof ServerPlayer player)) return true; // non-networked server actors/test fixtures
        if (player.connection == null || player instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            return true;
        }
        return AUTHORITY.get() == player;
    }

    public static void afterConnectionTick(ServerPlayer player, boolean awaitingTeleport) {
        if (awaitingTeleport || player.isRemoved() || player.isDeadOrDying()) return;
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        if (component.runtime().isInMove() || component.runtime().isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(player)) return;
        var oldRoute = GravityInfluencePolicy.collisionRoute(player);
        long oldEpoch = component.applicationEpoch();
        var oldPose = player.getPose();
        var oldBounds = player.getBoundingBox();
        Vec3 oldPosition = player.position();
        ServerPlayer previous = AUTHORITY.get();
        AUTHORITY.set(player);
        try {
            if (component.applicationBootstrapPending()) {
                PlayerPhysicalLoadBootstrap.bootstrapLoadedPlayer(player);
            } else {
                GravityApplicationCoordinator.updateAuthoritativeSuppression(player);
                var result = GravityApplicationCoordinator.updateBody(player);
                if (result.status() == GravityApplicationCoordinator.TransitionStatus.COMMITTED
                        || result.status() == GravityApplicationCoordinator.TransitionStatus.UNCHANGED) {
                    component.takePending();
                }
            }
        } finally {
            if (previous == null) AUTHORITY.remove(); else AUTHORITY.set(previous);
        }
        var newRoute = GravityInfluencePolicy.collisionRoute(player);
        boolean sameGeometry = oldRoute == newRoute
                && oldPose == player.getPose()
                && oldBounds.equals(player.getBoundingBox())
                && oldPosition.equals(player.position());
        boolean resyncRequested = RESYNC_PENDING.remove(player);
        if (sameGeometry && !resyncRequested) {
            if (newRoute == GravityInfluencePolicy.CollisionRoute.VANILLA
                    && oldEpoch != component.applicationEpoch()) {
                // Native AABB did not change. No teleport, ACK or motion reset.
                PacketDistributor.sendToPlayer(player,
                        ClientboundPlayerBodyCommitPayload.captureNativeApplication(player));
                PacketDistributor.sendToPlayersTrackingEntity(player,
                        ClientboundPlayerBodyCommitPayload.capture(player, null));
                GravityDebugLog.log(player, "native-application-commit",
                        "epoch=%s plan=%s route=%s position=%s velocity=%s",
                        component.applicationEpoch(), component.appliedPlan().kind(), newRoute,
                        GravityDebugLog.vec(player.position()),
                        GravityDebugLog.vec(player.getDeltaMovement()));
            }
            return;
        }

        // A real collider/position discontinuity. Match Vanilla absolute
        // teleport's zero velocity on BOTH sides; do not retain an old-mode impulse.
        player.setDeltaMovement(Vec3.ZERO);
        var target = player.position();
        GravityDebugLog.log(player, "body-handoff-commit",
                "epoch=%s custom=%s from=%s target=%s",
                component.applicationEpoch(), component.appliedPlan().usesCustomBody(),
                GravityDebugLog.vec(oldPosition), GravityDebugLog.vec(target));
        // Native teleport owns awaitingPosition, ID, timeout/resend and acknowledgement.
        // Its send seam attaches the committed body to this SAME correction.
        player.connection.teleport(target.x, target.y, target.z, player.getYRot(), player.getXRot());
        PacketDistributor.sendToPlayersTrackingEntity(player,
                ClientboundPlayerBodyCommitPayload.capture(player, null));
    }
}
