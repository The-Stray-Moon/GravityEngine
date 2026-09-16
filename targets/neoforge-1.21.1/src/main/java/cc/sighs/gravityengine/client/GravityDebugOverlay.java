package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.ClientConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

public final class GravityDebugOverlay {
    private GravityDebugOverlay() {}

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        var snapshot = GravityPresentationIntegration.snapshot(
                minecraft.player,
                event.getPartialTick().getGameTimeDeltaTicks()
        );
        var frame = snapshot.frame();
        event.getLeft().add(String.format(
                "GravityEngine Gravity: down=(%.3f, %.3f, %.3f) strength=%.3f tick=%d rev=%d%s",
                frame.down().x,
                frame.down().y,
                frame.down().z,
                frame.strength(),
                snapshot.tick(),
                snapshot.revision(),
                snapshot.fallback() ? " fallback" : ""
        ));
        event.getLeft().add(String.format("GravityEngine onGround=%b vel=(%.3f, %.3f, %.3f)", minecraft.player.onGround(), minecraft.player.getDeltaMovement().x, minecraft.player.getDeltaMovement().y, minecraft.player.getDeltaMovement().z));
        if (ClientConfig.gravityHitboxes) {
            event.getLeft().add(
                    "GravityEngine hitboxes: scope=APPLIED_APPLICATION"
            );
            event.getLeft().add(
                    "gray=entity AABB, cyan=physical body, yellow=presentation body, red=center delta"
            );
        }
        if (ClientConfig.bodyAttitudeDebug) {
            var attitude = ClientBodyAttitudeDiagnostics.debugSnapshot(
                    minecraft.player);
            if (attitude == null) {
                event.getLeft().add("GravityEngine Attitude: inactive/no snapshot");
            } else {
                var q = attitude.worldFromBody();
                var omega = attitude.angularVelocityWorld();
                event.getLeft().add(String.format(
                        "GravityEngine Attitude: sourceRole=%s authority=%s constraint=%s ownership=%s suspension=%s continuity=%s",
                        attitude.authorityRole(),
                        attitude.presentationAuthority(), attitude.constraint(),
                        attitude.ownership(), attitude.suspensionReason(),
                        attitude.continuity()));
                event.getLeft().add(String.format(
                        "  stream=%d config=%d logicalStep=%d serverTick=%d localRev=%d relayRev=%d",
                        attitude.streamEpoch(), attitude.configGeneration(),
                        attitude.lastLocalSimulationStep(),
                        attitude.authoritativeServerGameTick(),
                        attitude.localRevision(), attitude.authoritativeRevision()));
                event.getLeft().add(String.format(
                        "  roll=%.0f", attitude.rollInput()));
                event.getLeft().add(String.format(
                        "  q=(%.4f, %.4f, %.4f, %.4f) omega=(%.3f, %.3f, %.3f) view=(%.1f, %.1f)",
                        q.x, q.y, q.z, q.w, omega.x, omega.y, omega.z,
                        attitude.viewLocalYaw(), attitude.viewLocalPitch()));
                event.getLeft().add(String.format(
                        "  remoteAge=%s pending=%d sync=%s",
                        formatAge(attitude.remoteSnapshotAgeTicks()),
                        attitude.pendingPacketCount(),
                        attitude.lastSyncReason() == null
                                ? "n/a" : attitude.lastSyncReason()));
            }
        }
    }

    private static String formatAge(long ticks) {
        return ticks == Long.MAX_VALUE ? "n/a" : Long.toString(ticks);
    }
}
