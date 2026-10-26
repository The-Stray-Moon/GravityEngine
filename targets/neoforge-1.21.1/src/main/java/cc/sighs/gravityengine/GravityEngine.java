package cc.sighs.gravityengine;

import cc.sighs.gravityengine.event.BodyAttitudeEvents;
import cc.sighs.gravityengine.event.GravityEvents;
import cc.sighs.gravityengine.network.GravityEngineNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(GravityEngine.MOD_ID)
public final class GravityEngine {
    public static final String MOD_ID = "gravityengine";

    public GravityEngine(IEventBus modEventBus, ModContainer modContainer) {
        cc.sighs.gravityengine.gravity.debug.DebugStartup.report();
        modEventBus.addListener(this::registerPayloads);

        GravityEngineAttachments.ATTACHMENTS.register(modEventBus);

        NeoForge.EVENT_BUS.register(BodyAttitudeEvents.class);
        NeoForge.EVENT_BUS.register(GravityEvents.class);

        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                BodyAttitudeServerConfig.SPEC,
                "gravityengine-body-attitude.toml"
        );
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        GravityEngineNetwork.register(event);
    }
}
