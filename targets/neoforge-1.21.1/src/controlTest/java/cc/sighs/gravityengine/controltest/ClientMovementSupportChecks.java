package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;

import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.GravitySyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.CompletableFuture;

/** Real key states and LocalPlayer ticks in the isolated control-test world.
 * These checks exercise the existing Vanilla position packet loop on both sides. */
final class ClientMovementSupportChecks {
    private static final double STEEP_DOT = .014732121;
    private static final Vec3[] DOWN = {new Vec3(0,-.8,-.6),
            new Vec3(.00055,-.99367,-.11237).normalize(),
            new Vec3(-Math.sqrt(1-STEEP_DOT*STEEP_DOT),-STEEP_DOT,0)};
    private static int phase, ticks;
    private static CompletableFuture<Void> setup;
    private static Vec3 start;
    private static double previousSpeed;
    private static int frictionChecks;

    static boolean tick(Minecraft mc) {
        // This executable fixture owns its controls, including when the desktop
        // mouse/keyboard is used while the test client has focus.
        mc.options.keyLeft.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        if (setup == null) {
            var id = mc.player.getUUID();
            int cx = 160 + phase*40;
            Vec3 down = DOWN[phase];
            setup = CompletableFuture.runAsync(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                for (int x=-12;x<=12;x++) for (int z=-8;z<=8;z++)
                    player.serverLevel().setBlock(new BlockPos(cx+x,299,8+z), Blocks.STONE.defaultBlockState(),3);
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(cx,350,8.5,0,0);
                GravityApplicationCoordinator.applyDirectAssignment(player,new GravityState(down,.08));
                GravitySyncService.syncPlayer(player);
                player.connection.resumeFlushing();
            }, mc.getSingleplayerServer());
            return false;
        }
        if (!setup.isDone()) return false;
        setup.join();
        ++ticks;
        if (ticks == (phase == 2 ? 30 : 75)) {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "movement-support-phase-" + phase + ".png",
                    mc.getMainRenderTarget(), message -> System.out.println(message.getString()));
        }
        if (ticks == 20) {
            var id = mc.player.getUUID();
            int cx = 160 + phase*40;
            setup = CompletableFuture.runAsync(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var frame = GravityFrameAccess.authoritativeFrame(player);
                check(frame.down().distanceTo(DOWN[phase]) < 1e-9,"assignment ready");
                var destination = new Vec3(cx,300,8.5);
                var body = GravityEntityGeometry.candidateBody(GravityEntityGeometry.dimensions(player),destination,frame.up());
                destination = destination.add(0,300-body.enclosingAabb().minY(),0);
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(destination.x,destination.y,destination.z,0,0);
                player.connection.resumeFlushing();
            }, mc.getSingleplayerServer());
            return false;
        }
        var player = mc.player;
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
        var frame = GravityFrameAccess.authoritativeFrame(player);
        if (phase == 2) {
            if (ticks < 25) return false;
            if (ticks == 25) start = player.position();
            System.out.println("CLIENT_STEEP_OBSERVE tick=" + ticks + " P=" + player.position()
                    + " velocity=" + player.getDeltaMovement() + " onGround=" + player.onGround());
            check(!player.onGround(),"near-vertical contact must not ground");
            check(runtime.restingContactSnapshot() == null,"near-vertical face cannot retain stable anchoring");
            check(Math.abs(player.getDeltaMovement().x) > .03,"tangent gravity must survive: " + player.getDeltaMovement());
            if (ticks < 35) return false;
            check(player.position().distanceTo(start) > .2,"steep physical contact slides");
            System.out.println("CLIENT_STEEP_SLIDE_PASSED upDot=" + STEEP_DOT + " movement="
                    + player.position().subtract(start) + " velocity=" + player.getDeltaMovement());
            return true;
        }
        if (ticks < 60) return false;
        if (ticks == 60) start = player.position();
        mc.options.keyLeft.setDown(ticks < 85);
        check(player.onGround(),"stable foot support through tangential movement P=" + player.position());
        check(runtime.restingContactSnapshot() != null,"stable terminal face retained");
        double speed = Math.abs(frame.worldToLocal(player.getDeltaMovement()).x);
        if (ticks >= 86 && ticks <= 91 && previousSpeed > .006) {
            double ratio = speed / previousSpeed;
            check(Math.abs(ratio - .6F*.91F) < .002,"released-input damping=" + ratio);
            frictionChecks++;
            System.out.println("CLIENT_RELEASE_FRICTION phase=" + phase + " tick=" + ticks + " ratio=" + ratio);
        }
        previousSpeed = speed;
        if (ticks < 98) return false;
        check(frictionChecks >= 3,"multiple released-input friction ticks observed");
        check(player.position().distanceTo(start) > 1,"movement crosses finite block seams without freezing");
        System.out.println("CLIENT_SUPPORT_MOVEMENT_PASSED phase=" + phase + " displacement=" + player.position().subtract(start));
        phase++; ticks=0; frictionChecks=0; previousSpeed=0; setup=null;
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("support client phase=" + phase + " tick=" + ticks + " " + message);
    }
}
