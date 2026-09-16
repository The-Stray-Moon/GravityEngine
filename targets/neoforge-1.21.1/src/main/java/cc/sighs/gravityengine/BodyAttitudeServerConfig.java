package cc.sighs.gravityengine;

import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.network.BodyAttitudeReplicationService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Server-published control law for owning-client body-attitude production. */
@EventBusSubscriber(modid = GravityEngine.MOD_ID)
public final class BodyAttitudeServerConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    private static final ModConfigSpec.DoubleValue LOW_GRAVITY_SWIM_THRESHOLD = B.comment(
            "Gravity strength relative to Vanilla below which low-gravity swim eligibility opens. "
                    + "Ordinary ground/air motion is always reference-aligned and never depends on this ratio.")
            .defineInRange("lowGravitySwimThresholdRatio", .35, .01, 2);
    private static final ModConfigSpec.DoubleValue PITCH_RATE = d("elytraMaxPitchRateDegPerSec", 120, .01, 2000);
    private static final ModConfigSpec.DoubleValue YAW_RATE = d("elytraMaxYawRateDegPerSec", 120, .01, 2000);
    private static final ModConfigSpec.DoubleValue ROLL_RATE = B.comment(
            "Held controller roll speed in free attitude; also the Elytra roll rate cap.").defineInRange("controllerRollRateDegPerSec", 180, .01, 2000);
    private static final ModConfigSpec.DoubleValue ROLL_ACCEL = d("elytraRollAngularAccelerationDegPerSec2", 360, .01, 5000);
    private static final ModConfigSpec.DoubleValue GRAVITY_GAIN = B.comment("Belly-to-gravity rotation error (radians) times gain (s^-2) and capped g ratio gives rad/s^2.").defineInRange("elytraGravityAlignmentGainPerSecondSquared", 8.0, 0, 1000);
    private static final ModConfigSpec.DoubleValue ANGULAR_DRAG = B.comment("Passive angular drag coefficient in s^-1; applied to world angular velocity in rad/s.").defineInRange("elytraAngularDragPerSecond", 2.0, 0, 100);
    private static final ModConfigSpec.DoubleValue MAX_GRAVITY_SCALE = d("elytraMaxGravityScale", 4, 0, 100);
    private static final ModConfigSpec.DoubleValue FLIGHT_GAIN = B.comment(
            "Forward direction error (radians) times this gain (s^-2) gives flight alignment rad/s^2.")
            .defineInRange("elytraFlightAlignmentGainPerSecondSquared", 6.0, 0, 1000);
    private static final ModConfigSpec.DoubleValue ELYTRA_ACCEL = d("elytraMaxAngularAccelerationDegPerSec2", 720, .01, 3000);
    private static final ModConfigSpec.DoubleValue ELYTRA_SPEED = d("elytraMaxAngularSpeedDegPerSec", 300, .01, 2000);
    private static final ModConfigSpec.DoubleValue VELOCITY_ALIGNMENT = d("elytraVelocityAlignment", .35, 0, 1);
    private static final ModConfigSpec.DoubleValue VELOCITY_START = d("elytraVelocityAlignStartSpeed", .1, 0, 100);
    private static final ModConfigSpec.DoubleValue VELOCITY_FULL = d("elytraVelocityAlignFullSpeed", 1, .001, 100);
    private static final ModConfigSpec.DoubleValue QUAT_EPS = d("quaternionEpsilon", 1e-8, 1e-15, .1);
    private static final ModConfigSpec.DoubleValue VECTOR_EPS = d("vectorEpsilon", 1e-10, 1e-15, .1);
    private static final ModConfigSpec.DoubleValue MAX_SUBSTEP = d("maxSubstepSeconds", .05, .001, 1);
    private static final ModConfigSpec.IntValue MAX_SUBSTEPS = B.defineInRange("maxSubsteps", 8, 1, 64);
    public static final ModConfigSpec SPEC = B.build();

    private BodyAttitudeServerConfig() {}

    @SubscribeEvent static void onLoad(ModConfigEvent.Loading event) { refresh(event); }
    @SubscribeEvent static void onReload(ModConfigEvent.Reloading event) { refresh(event); }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.Generation generation =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.reloadServer(snapshot());

        MinecraftServer server =
                ServerLifecycleHooks.getCurrentServer();

        if (server != null) {
            server.execute(
                    () ->
                            BodyAttitudeReplicationService.broadcastConfig(
                                    server,
                                    generation
                            )
            );
        }
    }

    static BodyAttitudeConfigSnapshot snapshot() {
        return new BodyAttitudeConfigSnapshot(
                LOW_GRAVITY_SWIM_THRESHOLD.get(),
                PITCH_RATE.get(),
                YAW_RATE.get(),
                ROLL_RATE.get(),
                ROLL_ACCEL.get(),
                GRAVITY_GAIN.get(),
                ANGULAR_DRAG.get(),
                MAX_GRAVITY_SCALE.get(),
                FLIGHT_GAIN.get(),
                ELYTRA_ACCEL.get(),
                ELYTRA_SPEED.get(),
                VELOCITY_ALIGNMENT.get(),
                VELOCITY_START.get(),
                VELOCITY_FULL.get(),
                QUAT_EPS.get(),
                VECTOR_EPS.get(),
                MAX_SUBSTEP.get(),
                MAX_SUBSTEPS.get());
    }


    private static ModConfigSpec.DoubleValue d(String name, double value, double min, double max) {
        return B.defineInRange(name, value, min, max);
    }
}
