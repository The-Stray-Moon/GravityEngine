package cc.sighs.gravityengine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class ClientGravityLifecycle {
    private ClientGravityLifecycle() {}
    private static ClientLevel lastLevel;

    @SubscribeEvent public static void onClientTick(ClientTickEvent.Post ev) {
        ClientLevel cur = Minecraft.getInstance().level;
        if (lastLevel != null && lastLevel != cur) {
            clearStandTargets(lastLevel);
            if (lastLevel.dimension() != null) {
                ClientPlayerBodyCommitHandler.clearDimension(lastLevel.dimension().location());
            }
            ClientGravityFrameSampler.clearLevel(lastLevel);
        }
        lastLevel = cur;
        if (cur != null && !Minecraft.getInstance().isPaused()) {
            for (var entity : cur.entitiesForRendering()) ClientGravityFrameSampler.tick(entity);
        }
    }

    @SubscribeEvent public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut ev) {
        clearStandTargets(lastLevel);
        ClientPlayerBodyCommitHandler.clearAll();
        ClientGravityFrameSampler.clearAll();
        lastLevel = null;
    }

    private static void clearStandTargets(ClientLevel level) {
        if (level != null) for (var entity : level.entitiesForRendering()) {
            if (entity instanceof cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess stand)
                stand.gravityengine$clearPendingDimensions();
        }
    }

    @SubscribeEvent public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            clearStandTargets(level);
            ClientGravityFrameSampler.clearLevel(level);
            ClientPlayerBodyCommitHandler.clearDimension(level.dimension().location());
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientGravityFrameSampler.remove(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientGravityFrameSampler.remove(event.getEntity());
            ClientPlayerBodyCommitHandler.onEntityRemoved(event.getEntity());
            if (event.getEntity() instanceof cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess stand)
                stand.gravityengine$clearPendingDimensions();
        }
    }
}
