package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.attitude.runtime.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Actual client send boundary in an isolated integrated world, never loaded on the server. */
@EventBusSubscriber(modid = "gravityengine_control_tests", value = Dist.CLIENT)
public final class ClientControlBoundaryChecks {
    private static boolean opening, completed, stationaryCompleted;
    private static int ticks;
    private static double largestTilt;
    private static volatile String serverSneakFailure;
    private static volatile int serverSneakChecks;

    @SubscribeEvent
    public static void loaded(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (completed || minecraft.getOverlay() != null) return;
        try {
            if (!opening && minecraft.screen != null) {
                opening = true;
                Class.forName("net.minecraft.client.player.LocalPlayer");
                Class.forName("net.minecraft.client.multiplayer.ClientPacketListener");
                String name = "kinematic-attitude-" + System.currentTimeMillis();
                minecraft.createWorldOpenFlows().createFreshLevel(name,
                        new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), minecraft.screen);
                return;
            }
            if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) return;
            if (!BodyAttitudeRuntime.Access.component(minecraft.player).snapshot().authoritativeStreamOpen()) return;
            // This isolated fixture supplies synthetic keys. Host desktop focus changes must
            // not randomly drop its sprint edges/held roll; production focus gating is unchanged.
            minecraft.setWindowActive(true);
            ++ticks;
            if (Boolean.getBoolean("gravityengine.movementSupportChecks")) {
                minecraft.options.pauseOnLostFocus = false;
                minecraft.options.keyUp.setDown(false);
                minecraft.options.keyDown.setDown(false);
                minecraft.options.keyRight.setDown(false);
                minecraft.options.keySprint.setDown(false);
                if (!ClientMovementSupportChecks.tick(minecraft)) return;
                completed = true;
                java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"),
                        "PASS client released-input ground friction / oblique block edges / near-vertical physical slide");
                return;
            }
            if (ticks == 1) {
                minecraft.options.pauseOnLostFocus = false;
                org.lwjgl.glfw.GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
                var id = minecraft.player.getUUID();
                minecraft.getSingleplayerServer().execute(() -> {
                    var actor = minecraft.getSingleplayerServer().getPlayerList().getPlayer(id);
                    actor.getAbilities().invulnerable = true;
                    actor.onUpdateAbilities();
                    var base = actor.blockPosition();
                    int floorY = base.getY() + 2;
                    for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                        actor.serverLevel().setBlock(new net.minecraft.core.BlockPos(base.getX()+x, floorY, base.getZ()+z),
                                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                    actor.connection.teleport(base.getX()+.5, floorY+50, base.getZ()+.5, 0, 0);
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                            new cc.sighs.gravityengine.gravity.GravityState(new net.minecraft.world.phys.Vec3(0,-1,0), .001));
                    cc.sighs.gravityengine.network.GravitySyncService.syncPlayer(actor);
                    actor.connection.resumeFlushing();
                    System.out.println("CLIENT_FIXTURE_SERVER position=" + actor.position() + " assignment="
                            + ((cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess) actor).gravityengine$gravityComponent().assignedState());
                });
            }
            if (ticks == 10) ClientBodyStateSendChecks.run(minecraft);
            minecraft.options.keyUp.setDown(false); // Dedicated sprint intent works without forward movement.
            if (ticks == 30) minecraft.options.toggleSprint().set(true);
            minecraft.options.keyLeft.setDown(ticks >= 33 && ticks < 90);
            if (ticks == 33) {
                minecraft.options.keyShift.setDown(true);

            }
            if (ticks == 36 || ticks == 76) verifySneakOwnership(minecraft, ticks == 36);
            if (ticks == 40 || ticks == 85) {
                boolean expectedSneak = ticks == 85;
                var id = minecraft.player.getUUID();
                minecraft.getSingleplayerServer().execute(() -> {
                    var actor = minecraft.getSingleplayerServer().getPlayerList().getPlayer(id);
                    if (actor.isShiftKeyDown() != expectedSneak)
                        serverSneakFailure = "server semantic sneak mismatch: " + expectedSneak;
                    serverSneakChecks++;
                });
            }
            if (ticks == 90) {
                minecraft.options.keyShift.setDown(false);
                minecraft.options.keyUp.setDown(false);
                if (serverSneakFailure != null || serverSneakChecks != 2)
                    throw new AssertionError("network sneak checks: " + serverSneakFailure + " count=" + serverSneakChecks);
            }

            // Raw binding events, not the latched isDown value, select GravityEngine swim.
            if (ticks == 35 || ticks == 75) minecraft.options.keySprint.setDown(true);
            if (ticks == 45 || ticks == 80) minecraft.options.keySprint.setDown(false);
            if (ticks >= 20 && ticks < 100) minecraft.player.turn(0, 60);
            if (ticks == 40 || ticks == 60 || ticks == 85) {
                boolean swim = ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) minecraft.player)
                        .gravityengine$characterMode().swimActive();
                if (swim != (ticks < 75)) throw new AssertionError("sprint press/release toggle at " + ticks);
            }
            if (ticks == 135) {
                var id = minecraft.player.getUUID();
                minecraft.getSingleplayerServer().execute(() -> {
                    var actor = minecraft.getSingleplayerServer().getPlayerList().getPlayer(id);
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                            cc.sighs.gravityengine.gravity.GravityState.DEFAULT);
                    cc.sighs.gravityengine.network.GravitySyncService.syncPlayer(actor);
                    actor.connection.resumeFlushing();
                    System.out.println("CLIENT_FIXTURE_SERVER position=" + actor.position() + " assignment="
                            + ((cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess) actor).gravityengine$gravityComponent().assignedState());
                });
            }
            cc.sighs.gravityengine.client.ClientBodyAttitudeKeys.ROLL_CLOCKWISE.setDown(ticks >= 20 && ticks < 125);
            if (ticks == 60) verifyRollInterpolation(minecraft);
            if (ticks == 5 || ticks == 35) System.out.println("CLIENT_FREE_CONTRACT sprint=" + minecraft.player.isSprinting()
                    + " position=" + minecraft.player.position() + " playerTick=" + minecraft.player.tickCount
                    + " assignedStrength=" + ((cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess) minecraft.player).gravityengine$gravityComponent().assignedState().strength()
                    + " ground=" + minecraft.player.onGround() + " paused=" + minecraft.isPaused()
                    + " serverPaused=" + minecraft.getSingleplayerServer().isPaused() + " focus=" + minecraft.isWindowActive() + " frame="
                    + cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(minecraft.player)
                    + " actor=" + BodyAttitudeRuntime.Access.component(minecraft.player).ownership());
            var q = BodyAttitudeRuntime.Access.component(minecraft.player).state().currentWorldFromBody();
            largestTilt = Math.max(largestTilt, cc.sighs.gravityengine.math.geometry.BodyOrientation3d.angularDistance(
                    new org.joml.Quaterniond(), q));
            if (ticks < 150) return;
            if (largestTilt < .001) throw new AssertionError("integrated roll input did not rotate the body");
            if (ticks == 150 && BodyAttitudeRuntime.Access.component(minecraft.player).ownership() != BodyAttitudeOwnership.INACTIVE)
                throw new AssertionError("default reference must release actor root immediately");
            if (!stationaryCompleted) {
                minecraft.options.keyLeft.setDown(false);
                minecraft.options.keyRight.setDown(false);
                minecraft.options.keyDown.setDown(false);
                minecraft.options.keyJump.setDown(false);
                minecraft.options.keyShift.setDown(false);
                minecraft.player.setYRot(0);
                minecraft.player.setXRot(0);
                if (!ClientStationaryChecks.tick(minecraft)) return;
                stationaryCompleted = true;
            }
            if (!ClientMovementSupportChecks.tick(minecraft)) return;
            completed = true;
            System.out.println("INTEGRATED_KINEMATIC_ATTITUDE_COMPLETED ticks=" + ticks + " maxTiltRadians=" + largestTilt);
            System.out.println("CONTROL_BOUNDARY_CLIENT_MIXINS_PASSED");
            java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"),
                    "PASS client same-step Shift descend/sneak transitions and server state / independent controller/body / Vanilla Toggle Sprint enabled / dedicated sprint toggle without forward / held and released sprint / full pitch loops / multi-turn roll with partialTick interpolation / reference release / stationary default-cardinal-oblique packet loop / released input / oblique edges / steep slide");
        } catch (Throwable failure) {
            completed = true;
            failure.printStackTrace();
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"), "FAIL " + failure); }
            catch (java.io.IOException ignored) {}
        } finally {
            if (completed) {
                minecraft.options.keyUp.setDown(false);
                minecraft.options.keyLeft.setDown(false);
                minecraft.options.keySprint.setDown(false);
                cc.sighs.gravityengine.client.ClientBodyAttitudeKeys.ROLL_CLOCKWISE.setDown(false);
                minecraft.stop();
            }
        }
    }

    private static void verifySneakOwnership(Minecraft minecraft, boolean swim) {
        var player = minecraft.player;
        var control = (cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player;
        var plan = control.gravityengine$characterControl().at(player.tickCount);
        if (plan == null || plan.ownsDescendInput() != swim)
            throw new AssertionError("same-step swim plan at fixture tick " + ticks + ": " + plan);
        if (!((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess.LocalInput) player).gravityengine$descendHeld())
            throw new AssertionError("held physical descend lost");
        if (player.input.shiftKeyDown == swim || player.isShiftKeyDown() == swim
                || player.isSteppingCarefully() == swim || player.isSuppressingBounce() == swim
                || player.isDiscrete() == swim || player.isSecondaryUseActive() == swim)
            throw new AssertionError("incoherent native sneak consumers at " + ticks);
        if (player.isCrouching() == swim || (player.getPose() == net.minecraft.world.entity.Pose.CROUCHING) == swim)
            throw new AssertionError("crouch latch at " + ticks + ": " + player.getPose());
        float expected = swim ? 1 : (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
        if (Math.abs(player.input.leftImpulse - expected) > 1e-6)
            throw new AssertionError("sneak speed on transition: " + player.input.leftImpulse);
        System.out.println("CLIENT_SNEAK_OWNERSHIP_PASSED swim=" + swim + " tick=" + ticks);
    }

    private static void verifyRollInterpolation(Minecraft minecraft) {
        var player = minecraft.player;
        var owner = BodyAttitudeRuntime.Access.component(player).snapshot();
        var a = cc.sighs.gravityengine.client.BodyRenderPoseResolver.snapshot(player, 0);
        var b = cc.sighs.gravityengine.client.BodyRenderPoseResolver.snapshot(player, .5f);
        var c = cc.sighs.gravityengine.client.BodyRenderPoseResolver.snapshot(player, 1);
        if (a == null || b == null || c == null) throw new AssertionError("missing swim roll presentation");
        double step = BodyAttitudeRuntime.Config.forPlayer(player).orElseThrow().controllerRollRateRadiansPerSecond()
                * BodyAttitudeRuntime.Service.GAME_TICK_SECONDS;
        double first = cc.sighs.gravityengine.client.BodyAttitudeInterpolation.angularDistance(
                a.cameraView().worldFromController(), b.cameraView().worldFromController());
        double second = cc.sighs.gravityengine.client.BodyAttitudeInterpolation.angularDistance(
                b.cameraView().worldFromController(), c.cameraView().worldFromController());
        if (Math.abs(first - step / 2) > 1e-6 || Math.abs(second - step / 2) > 1e-6)
            throw new AssertionError("held roll must interpolate within the real LocalPlayer tick: " + first + ", " + second);
        if (owner != BodyAttitudeRuntime.Access.component(player).snapshot())
            throw new AssertionError("roll presentation mutated simulation");
        System.out.println("CLIENT_ROLL_INTERPOLATION_PASSED halfTickRadians=" + first);
    }
}
