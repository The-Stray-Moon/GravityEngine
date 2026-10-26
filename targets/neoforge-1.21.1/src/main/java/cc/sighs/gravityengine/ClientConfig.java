package cc.sighs.gravityengine;

import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeVisualConfigSnapshot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only visual and diagnostic settings. */
@EventBusSubscriber(modid = GravityEngine.MOD_ID, value = Dist.CLIENT)
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER =
            new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue GRAVITY_HITBOXES =
            BUILDER
                    .comment(
                            "Render GravityEngine gravity diagnostics for entities "
                                    + "with an active committed gravity application."
                    )
                    .define("debug.gravityHitboxes", false);

    private static final ModConfigSpec.BooleanValue ATTITUDE_DEBUG =
            BUILDER.define("debug.bodyAttitude", false);
    private static final ModConfigSpec.DoubleValue REMOTE_MIN_DURATION =
            BUILDER.comment("Remote entity tick approach bound; rendered with Vanilla partial ticks.").defineInRange(
                    "bodyAttitude.remoteInterpolationMinTicks",
                    1.0D, 0.05D, 20.0D);
    private static final ModConfigSpec.DoubleValue REMOTE_MAX_DURATION =
            BUILDER.comment("Remote entity tick approach bound; rendered with Vanilla partial ticks.").defineInRange(
                    "bodyAttitude.remoteInterpolationMaxTicks",
                    4.0D, 0.05D, 40.0D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static volatile boolean gravityHitboxes;
    public static volatile boolean bodyAttitudeDebug;
    public static volatile cc.sighs.gravityengine.attitude.presentation.BodyAttitudeVisualConfigSnapshot
            bodyAttitudeVisual = BodyAttitudeVisualConfigSnapshot.DEFAULT;

    private ClientConfig() {}

    public static void refresh() {
        gravityHitboxes = GRAVITY_HITBOXES.get();
        bodyAttitudeDebug = ATTITUDE_DEBUG.get();
        double remoteMin = REMOTE_MIN_DURATION.get();
        double remoteMax = Math.max(remoteMin, REMOTE_MAX_DURATION.get());
        bodyAttitudeVisual = new BodyAttitudeVisualConfigSnapshot(remoteMin, remoteMax);
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        refreshIfOwnConfig(event);
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        refreshIfOwnConfig(event);
    }

    private static void refreshIfOwnConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }
}
