package cc.sighs.gravityengine;

import cc.sighs.gravityengine.client.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = GravityEngine.MOD_ID, dist = Dist.CLIENT)
public final class GravityEngineClient {
    public GravityEngineClient(
            IEventBus modEventBus,
            ModContainer container
    ) {
        /*
         * Install physical-client implementations before any play connection
         * can receive a GravityEngine clientbound payload.
         *
         * Common payload type/codec registration remains in GravityEngineNetwork.
         */
        ClientNetworkPayloadHandlers.install();

        container.registerConfig(
                ModConfig.Type.CLIENT,
                ClientConfig.SPEC
        );

        modEventBus.addListener(ClientBodyAttitudeKeys::register);

        NeoForge.EVENT_BUS.register(ClientBodyAttitudeLifecycle.class);
        NeoForge.EVENT_BUS.register(GravityDebugOverlay.class);
        NeoForge.EVENT_BUS.register(GravityDebugHitboxes.class);
        NeoForge.EVENT_BUS.register(ClientGravityLifecycle.class);
    }
}
