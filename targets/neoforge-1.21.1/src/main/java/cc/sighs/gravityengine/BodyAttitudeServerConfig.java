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
    private static final ModConfigSpec.DoubleValue ROLL_RATE = B.comment(
            "Held controller roll speed for the geometric SemanticView / FREE_ATTITUDE path only. "
                    + "Elytra physical roll is a torque, not this rate.")
            .defineInRange("controllerRollRateDegPerSec", 180, .01, 2000);
    private static final ModConfigSpec.DoubleValue ELYTRA_INERTIA = B.comment(
            "Effective isotropic angular inertia of the dynamic Elytra flight attitude, in "
                    + "rotational-inertia game units. Not entity mass.")
            .defineInRange("elytraEffectiveAngularInertia", 1.0, 1.0E-6, 1.0E4);
    private static final ModConfigSpec.DoubleValue ELYTRA_ROLL_TORQUE = B.comment(
            "Sustained player roll torque about the physical flight forward axis, in torque game "
                    + "units (inertia-unit * rad/s^2). Migrated from 360 deg/s^2 at I = 1, i.e. "
                    + "tau = I * alpha = 6.283185307179586.")
            .defineInRange("elytraRollTorque", 6.283185307179586, 0, 1.0E4);
    private static final ModConfigSpec.DoubleValue ELYTRA_HEADING_GAIN = B.comment(
            "Heading proportional torque per radian of flight-forward direction error, in torque "
                    + "game units. Migrated from the previous s^-2 flight alignment gain through "
                    + "tau = I * alpha.")
            .defineInRange("elytraHeadingTorqueGain", 6.0, 0, 1.0E4);
    private static final ModConfigSpec.DoubleValue ELYTRA_HEADING_DAMPING = B.comment(
            "Heading-only derivative damping torque per rad/s of the angular velocity component "
                    + "that changes the flight forward direction. It never brakes axial roll.")
            .defineInRange("elytraHeadingDamping", 0.0, 0, 1.0E3);
    private static final ModConfigSpec.DoubleValue ELYTRA_ANGULAR_DAMPING = B.comment(
            "Generic game angular damping coefficient k in s^-1 with tau = -k * L_world. "
                    + "Zero disables it exactly and restores momentum conservation.")
            .defineInRange("elytraAngularDamping", 2.0, 0, 100);
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
                ROLL_RATE.get(),
                ELYTRA_INERTIA.get(),
                ELYTRA_ROLL_TORQUE.get(),
                ELYTRA_HEADING_GAIN.get(),
                ELYTRA_HEADING_DAMPING.get(),
                ELYTRA_ANGULAR_DAMPING.get(),
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
