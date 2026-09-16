package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;

import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.GravitySyncService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Real LocalPlayer ticks, position sends, integrated-server PLAYER moves and
 * correction packets on static support, including 100 ms of ordered position
 * packet delay during the resting observation. Only present in the opt-in test mod. */
final class ClientStationaryChecks {
    private static final Vec3[] DOWN = {new Vec3(0, -1, 0), new Vec3(1, 0, 0),
            new Vec3(-.0226, -.98, -.2).normalize()};
    private static final AtomicInteger corrections = new AtomicInteger();
    private static final AtomicInteger delayedPackets = new AtomicInteger();
    private static volatile boolean latencyEnabled;
    private static int baselineDelayedPackets;
    private static int phase, ticks, baselineCorrections;
    private static Vec3 start;
    private static long shapeRevision;
    private static CompletableFuture<Snapshot> serverRead;
    private static Snapshot clientRead;
    private static CompletableFuture<Void> setup;

    private record Snapshot(Vec3 position, Vec3 down,
                            cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule body, boolean grounded) {}

    static boolean tick(Minecraft mc) {
        if (setup == null) {
            if (phase == 0) mc.player.connection.getConnection().channel().pipeline()
                    .addBefore("packet_handler", "stationary_corrections", new ChannelDuplexHandler() {
                        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                            if (message instanceof ClientboundPlayerPositionPacket) corrections.incrementAndGet();
                            super.channelRead(context, message);
                        }
                        @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise) throws Exception {
                            if(latencyEnabled && message instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket) {
                                delayedPackets.incrementAndGet();
                                context.executor().schedule(()->context.writeAndFlush(message,promise),100,
                                        java.util.concurrent.TimeUnit.MILLISECONDS);
                            } else super.write(context,message,promise);
                        }
                    });
            Vec3 down = DOWN[phase];
            var id = mc.player.getUUID();
            setup = CompletableFuture.runAsync(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var level = player.serverLevel();
                // Separate fixtures so an earlier wall cannot overlap a later floor.
                int cx = 32 + phase * 32;
                for (int a = -5; a <= 5; a++) for (int b = -5; b <= 5; b++) {
                    BlockPos block = phase == 1 ? new BlockPos(cx + 1, 300 + a, 8 + b)
                            : new BlockPos(cx + a, 299, 8 + b);
                    level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
                }
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(cx - 3, 350, 8.5, 0, 0);
                GravityApplicationCoordinator.applyDirectAssignment(player, new GravityState(down, .08));
                GravitySyncService.syncPlayer(player);
                player.connection.resumeFlushing();
            }, mc.getSingleplayerServer());
            return false;
        }
        if (!setup.isDone()) return false;
        setup.join();
        ++ticks;
        if (ticks == 20) {
            var id = mc.player.getUUID();
            setup = CompletableFuture.runAsync(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var frame = GravityFrameAccess.authoritativeFrame(player);
                check(frame.down().distanceTo(DOWN[phase]) < 1e-10, "fixture frame settled in clear space");
                int cx = 32 + phase * 32;
                // Normal operations on both sides establish tangent continuity
                // before the support teleport. Do not seed a canonical frame on one side.
                var dimensions = GravityEntityGeometry.dimensions(player);
                var destination = new Vec3(cx - 3, 300, 8.5);
                var body = GravityEntityGeometry.candidateBody(dimensions, destination, frame.up());
                destination = phase == 1
                        ? destination.add(cx + 1 - body.enclosingAabb().maxX(), 4, 0)
                        : destination.add(0, 300 - body.enclosingAabb().minY(), 0);
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(destination.x, destination.y, destination.z, 0, 0);
                player.connection.resumeFlushing();
            }, mc.getSingleplayerServer());
            return false;
        }
        if (ticks < 60) return false; // assignment, chunks, teleport acknowledgement and contact settle
        var player = mc.player;
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
        var frame = GravityFrameAccess.authoritativeFrame(player);
        if (ticks == 60) {
            start = player.position(); shapeRevision = runtime.bodyShapeRevision();
            baselineCorrections = corrections.get();
            baselineDelayedPackets=delayedPackets.get(); latencyEnabled=true;
        }
        // Drain delayed writes before the next fixture's teleport/ack lifecycle.
        if(ticks==155) latencyEnabled=false;
        check(player.onGround(), "client supported P=" + player.position() + " frame=" + frame.down());
        check(player.position().distanceTo(start) < 1e-7, "client fixed P start=" + start + " actual=" + player.position());
        Vec3 local = frame.worldToLocal(player.getDeltaMovement());
        check(Math.abs(local.x) < 1e-10 && Math.abs(local.z) < 1e-10, "client tangent velocity=" + local);
        check(runtime.bodyShapeRevision() == shapeRevision, "client shape churn");
        check(corrections.get() == baselineCorrections, "recurring position correction");
        if (serverRead != null && serverRead.isDone()) {
            Snapshot server = serverRead.join();
            check(server.grounded(), "server supported");
            check(server.position().distanceTo(clientRead.position()) < 1e-7, "server/client P agreement");
            check(server.down().distanceTo(clientRead.down()) < 1e-12, "server/client collision axis agreement");
            check(server.body().radius()==clientRead.body().radius()
                    && server.body().halfSegmentLength()==clientRead.body().halfSegmentLength()
                    && server.body().axis().distance(clientRead.body().axis())<1e-12,
                    "server/client exact capsule agreement independent of tangent gauge server="+server.body()+" client="+clientRead.body());
            serverRead = null;
        }
        if (serverRead == null) {
            clientRead = new Snapshot(player.position(), frame.down(), GravityEntityGeometry.exactBody(player,frame), player.onGround());
            var id = player.getUUID();
            serverRead = CompletableFuture.supplyAsync(() -> {
                var server = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var reference = GravityFrameAccess.authoritativeFrame(server);
                return new Snapshot(server.position(), reference.down(), GravityEntityGeometry.exactBody(server,reference), server.onGround());
            }, mc.getSingleplayerServer());
        }
        if (ticks < 160) return false;
        check(delayedPackets.get()>baselineDelayedPackets,"latency fixture delayed real position packets");
        System.out.println("CLIENT_STATIONARY_PASSED phase=" + phase + " ticks=100 P=" + start
                + " corrections=" + (corrections.get() - baselineCorrections)
                + " packetDelayMillis=100 delayedPackets="+(delayedPackets.get()-baselineDelayedPackets));
        ++phase; ticks = 0; setup = null; serverRead = null;
        if (phase < DOWN.length) return false;
        mc.player.connection.getConnection().channel().pipeline().remove("stationary_corrections");
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("stationary phase=" + phase + " tick=" + ticks + " " + message);
    }
}
