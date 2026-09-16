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
            if (lastLevel.dimension() != null) {
                PendingSnapshotStore.clearDimension(lastLevel.dimension().location());
                ClientPlayerBodyCommitHandler.clearDimension(lastLevel.dimension().location());
            }
            ClientGravityFrameSampler.clearLevel(lastLevel);
        }
        lastLevel = cur;
        PendingSnapshotStore.retryOnTick();
        ClientPlayerBodyCommitHandler.retryOnTick();
        if (cur != null && !Minecraft.getInstance().isPaused()) {
            for (var entity : cur.entitiesForRendering()) ClientGravityFrameSampler.tick(entity);
        }
    }

    @SubscribeEvent public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut ev) {
        PendingSnapshotStore.clearAll();
        ClientPlayerBodyCommitHandler.clearAll();
        ClientGravityFrameSampler.clearAll();
        lastLevel = null;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientGravityFrameSampler.remove(event.getEntity());
            PendingSnapshotStore.onEntityJoin(event.getEntity());
            ClientPlayerBodyCommitHandler.onEntityJoin(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientGravityFrameSampler.remove(event.getEntity());
            PendingSnapshotStore.onEntityRemoved(event.getEntity());
            ClientPlayerBodyCommitHandler.onEntityRemoved(event.getEntity());
        }
    }
}
