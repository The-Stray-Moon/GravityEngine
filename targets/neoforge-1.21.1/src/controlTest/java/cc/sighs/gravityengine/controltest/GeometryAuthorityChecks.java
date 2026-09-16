package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.network.BodyAttitudeStateReceiver;
import cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaterniond;
import java.util.UUID;

/** Actual transformed Vanilla packet/travel invocations in the isolated smoke world. */
final class GeometryAuthorityChecks {
    private GeometryAuthorityChecks() {}

    static void run(ServerLevel level) {
        phantomEdge(level);
        oldPhantomMovedWrongly(level);
        realAndExistingCollision(level);
        vanillaParity(level);
        supportedRest(level);
        nativeTravelAfterTeleport(level);
        finiteTopEdge(level);
        steepSupportedRest(level);
        aiStepNormalVelocity(level);
        supportedFramePacketParity(level);
        stationaryAiStepPackets(level);
        independentAttitudeAndMovement(level, false);
        independentAttitudeAndMovement(level, true);
        centerFixedBody(level);
        System.out.println("GEOMETRY_AUTHORITY_CHECKS_PASSED");
    }

    private static void phantomEdge(ServerLevel level) {
        BlockPos block = new BlockPos(7, 299, 8);
        var saved = level.getBlockState(block);
        var player = player(level, "ExactPacket", new Vec3(8.2, 300.0005 + (.3 + .6 / Math.sqrt(2)) - .9, 8.5),
                new Quaterniond().rotationZ(Math.PI / 4));
        try {
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 18);
            var requested = player.position().add(0, -.001, .1);
            var old = VanillaBodyOccupancy.capture(player);
            var target = VanillaBodyOccupancy.atRequestedPosition(old, player.position(), requested);
            var shape = Shapes.create(new net.minecraft.world.phys.AABB(block));
            check(cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter.toMinecraft(target.enclosingAabb())
                    .intersects(new net.minecraft.world.phys.AABB(block)), "requested proxy enters block-top layer");
            check(!VanillaBodyOccupancy.occupies(target, shape), "requested exact body clears the edge");
            send(player, requested);
            near(requested, player.position(), "native phantom edge packet must be accepted");
            check(player.playerMoves == 1, "phantom edge uses exactly one PLAYER move");
        } finally {
            level.setBlock(block, saved, 18);
            player.discard();
        }
    }

    private static void finiteTopEdge(ServerLevel level) {
        var block = new BlockPos(8,299,8);
        var saved = level.getBlockState(block);
        var actor = player(level,"FiniteEdge",new Vec3(7.5,300,8.5),new Quaterniond());
        try {
            level.setBlock(block,Blocks.STONE.defaultBlockState(),18);
            double halfWidth = actor.getBbWidth()/2.0;
            actor.setPos(8-halfWidth,300,8.5);
            Vec3 start = actor.position();
            actor.setDeltaMovement(new Vec3(.1,0,0));
            actor.move(MoverType.SELF,new Vec3(.1,0,0));
            near(start.add(.1,0,0),actor.position(),"native exact top-edge entry survives");
            near(new Vec3(.1,0,0),actor.getDeltaMovement(),"native post-move velocity survives top edge");
            check(!actor.horizontalCollision,"top edge cannot fabricate movement clipping");
            actor.setPos(8-halfWidth,299.99,8.5);
            start = actor.position();
            actor.setDeltaMovement(new Vec3(.1,0,0));
            actor.move(MoverType.SELF,new Vec3(.1,0,0));
            near(start,actor.position(),"real side volume still blocks");
            near(Vec3.ZERO,actor.getDeltaMovement(),"real side volume clips persistent velocity");
            check(actor.horizontalCollision,"actual side clipping publishes movement flag");
        } finally { level.setBlock(block,saved,18); actor.discard(); }
    }

    private static void oldPhantomMovedWrongly(ServerLevel level) {
        BlockPos block = new BlockPos(7, 299, 8);
        var saved = level.getBlockState(block);
        // Old proxy overlaps a block-top layer. Crossing it is physically
        // clipped, while the requested endpoint beyond the block is clear.
        var player = player(level, "OldOccupancy", new Vec3(8.2, 299.99 + (.3 + .6 / Math.sqrt(2)) - .9, 8.5),
                new Quaterniond().rotationZ(Math.PI / 4));
        try {
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 18);
            Vec3 start = player.position(), requested = start.add(-3, 0, 0);
            var old = VanillaBodyOccupancy.capture(player);
            var shape = Shapes.create(new net.minecraft.world.phys.AABB(block));
            check(player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(block)), "old proxy phantom fixture");
            check(!VanillaBodyOccupancy.occupies(old, shape), "old exact body is clear");
            check(!VanillaBodyOccupancy.occupies(VanillaBodyOccupancy.atRequestedPosition(old, start, requested), shape),
                    "endpoint is clear, so only moved-wrongly policy rejects");
            // Force a known post-move mismatch so native moved-wrongly policy is exercised.
            player.disturbAfterMove = true;
            send(player, requested);
            near(start, player.position(), "old proxy phantom must not exempt moved-wrongly");
            check(player.playerMoves == 1, "moved-wrongly correction uses one PLAYER move");
        } finally {
            level.setBlock(block, saved, 18);
            player.discard();
        }
    }

    private static void realAndExistingCollision(ServerLevel level) {
        BlockPos block = new BlockPos(9, 300, 8);
        var saved = level.getBlockState(block);
        var player = player(level, "NewOccupancy", new Vec3(8.5, 300, 8.5), new Quaterniond());
        try {
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 18);
            Vec3 start = player.position();
            // .01 penetration leaves moved-wrongly below threshold; the
            // existing new-collision expression must reject this endpoint.
            send(player, new Vec3(8.71, 300, 8.5));
            near(start, player.position(), "native real new occupancy must correct");
            check(player.playerMoves == 1, "real new collision uses one PLAYER move");
        } finally {
            level.setBlock(block, saved, 18);
            player.discard();
        }
        BlockPos occupied = new BlockPos(8, 300, 8);
        saved = level.getBlockState(occupied);
        player = player(level, "ExistingOccupancy", new Vec3(8.1, 300, 8.5), new Quaterniond());
        try {
            level.setBlock(occupied, Blocks.STONE.defaultBlockState(), 18);
            Vec3 requested = player.position().add(.4, 0, 0);
            send(player, requested);
            near(requested, player.position(), "Vanilla whole-shape existing overlap exemption survives");
            check(player.playerMoves == 1, "existing occupancy uses one PLAYER move");
        } finally {
            level.setBlock(occupied, saved, 18);
            player.discard();
        }
    }

    private static void vanillaParity(ServerLevel level) {
        var player = player(level, "Dinnerbone", new Vec3(8.5, 300, 8.5), null);
        try {
            check(VanillaBodyOccupancy.capture(player) == null, "inactive player delegates occupancy to Vanilla");
            var requested = player.position().add(.1, 0, 0);
            send(player, requested);
            near(requested, player.position(), "inactive native packet parity");
            check(player.playerMoves == 1, "inactive packet uses one native move");
        } finally { player.discard(); }
    }

    private static void nativeTravelAfterTeleport(ServerLevel level) {
        var actor = player(level, "NativeTeleport", new Vec3(8.5, 350, 8.5), new Quaterniond());
        try {
            GravityApplicationCoordinator.applyDirectAssignment(actor,
                    new GravityState(GravityState.DEFAULT_DOWN, .001));
            actor.setOnGround(true);
            GravityEntityAccess.cast(actor).gravityengine$setVanillaSupportingBlock(new BlockPos(8, 299, 8));
            actor.setDeltaMovement(new Vec3(0, -.05, 0));
            var before = actor.position();
            actor.travel(Vec3.ZERO);
            near(before.add(0, -.05, 0), actor.position(), "native travel survives distant pre-teleport support");
            check(!actor.onGround(), "native move clears old ground at the airborne destination");
            check(GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().geometryReferenceFrame().strength() == .001,
                    "native reference publishes fresh environmental strength");
        } finally { actor.discard(); }
    }

    private static void supportedRest(ServerLevel level) {
        BlockPos block = new BlockPos(8, 299, 8);
        var saved = level.getBlockState(block);
        // Default-direction fields retain the native stationary character fixed point.
        var player = player(level, "SupportedRest", new Vec3(8.5, 300, 8.5),
                new Quaterniond());
        try {
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 18);
            var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
            Vec3 start = player.position();
            for (int tick = 0; tick < 200; tick++) {
                player.tickCount++;
                player.setDeltaMovement(0,-.08*.98F,0);
                player.travel(Vec3.ZERO);
                near(start, player.position(), "native supported fixed P tick=" + tick);
                near(new Vec3(0,-.08*.98F,0), player.getDeltaMovement(), "native supported velocity tick=" + tick);
                check(player.onGround(), "completed support retained");
                check(!player.horizontalCollision, "support does not manufacture hard flags");
                check(!runtime.isInMove(), "travel closes its operation");
            }
            // A source outside the gravity increment still reaches the OBB solver.
            player.setDeltaMovement(0, -.02, 0);
            player.travel(Vec3.ZERO);
            near(start, player.position(), "floor blocks explicit downward movement");
        } finally {
            level.setBlock(block, saved, 18);
            player.discard();
        }
    }

    private static void steepSupportedRest(ServerLevel level) {
        var block=new BlockPos(8,299,8); var saved=level.getBlockState(block);
        var actor=player(level,"SteepRest",new Vec3(8.5,300,8.5),new Quaterniond());
        try {
            level.setBlock(block,Blocks.STONE.defaultBlockState(),18);
            GravityApplicationCoordinator.applyDirectAssignment(actor,
                    new GravityState(new Vec3(-.2,-Math.sqrt(.96),0),.08));
            var runtime=GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
            var fixtureFrame=GravityFrame.fromDown(new Vec3(-.2,-Math.sqrt(.96),0),.08);
            var body=cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.exactBody(actor,fixtureFrame);
            actor.setPos(actor.position().add(0,300-body.enclosingAabb().minY(),0));
            cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.installFromPositionAnchor(actor,fixtureFrame,actor.position());
            actor.setDeltaMovement(runtime.geometryReferenceFrame().down().scale(.1));
            actor.travel(Vec3.ZERO);
            Vec3 start=actor.position();
            for (int tick=0;tick<300;tick++) {
                actor.tickCount++;
                actor.travel(Vec3.ZERO);
                near(start,actor.position(),"native steep support fixed P tick="+tick);
                near(Vec3.ZERO,actor.getDeltaMovement(),"native steep support absorbs new gravity tick="+tick);
                check(actor.onGround() && runtime.restingContactSnapshot()!=null,"oblique reference retains real support");
                check(!runtime.isInMove(),"steep travel closes operation");
            }
        } finally { level.setBlock(block,saved,18); actor.discard(); }
    }

    private static void aiStepNormalVelocity(ServerLevel level) {
        var actor = player(level, "AiNormal", new Vec3(8.5, 310, 8.5), null);
        try {
            Vec3 down = new Vec3(-.0226, -.98, -.2).normalize();
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(down, .08));
            var frame = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().geometryReferenceFrame();
            actor.setDeltaMovement(frame.down().scale(.09856));
            actor.aiStep();
            Vec3 local = frame.worldToLocal(actor.velocityAtTravel);
            check(Math.abs(local.x) < 1e-12 && Math.abs(local.z) < 1e-12,
                    "aiStep must not manufacture tangent velocity: " + local);
            check(Math.abs(local.y + .09856) < 1e-12, "aiStep preserves normal above threshold");
        } finally { actor.discard(); }
    }

    /** Actual SELF and handleMovePlayer invocations from matching start states.
     * Packet operands are the client's resulting P; this does not simulate LocalPlayer sending. */
    private static void supportedFramePacketParity(ServerLevel level) {
        var block = new BlockPos(8, 299, 8);
        var adjacent = block.east();
        var saved = level.getBlockState(block); var savedAdjacent = level.getBlockState(adjacent);
        try {
            double band = GravityGeometryTransitionService.COLLISION_AXIS_ANGULAR_EPSILON_RADIANS;
            for (int surface = 0; surface < 3; surface++) {
                level.setBlock(block, surface == 2 ? Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.WEST)
                        : Blocks.STONE.defaultBlockState(), 18);
                level.setBlock(adjacent, surface == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 18);
                double x = surface == 1 ? 9 : 8.5;
                for (double angle : new double[]{band * .999, band * 1.001, -band * .999, -band * 1.001, .01, -.01}) {
                    var installed = GravityFrame.fromDown(new Vec3(-.7, -.714, -.02).normalize(), .08);
                    var down = new Quaterniond().rotationZ(angle).transform(new org.joml.Vector3d(
                            installed.down().x, installed.down().y, installed.down().z));
                    var proposed = new GravityState(new Vec3(down.x, down.y, down.z), .08);
                    var self = supportedActor(level, "FrameSelf", installed, x);
                    Vec3 start = self.position();
                    GravityFrame selected;
                    Vec3 target;
                    try {
                        evolveEnvironment(self, proposed);
                        self.move(MoverType.SELF, installed.down().scale(.01));
                        target = self.position(); selected = runtime(self).geometryReferenceFrame();
                        check(self.recovery.lengthSqr() == 0, "SELF frame evolution requires no recovery");
                        check(!VanillaBodyOccupancy.hasNewCollision(level, self, self.oldMoveBody,
                                target.x, target.y, target.z), "SELF endpoint is exact-body legal");
                    } finally { self.discard(); }
                    var server = supportedActor(level, "FramePacket", installed, x);
                    try {
                        near(start, server.position(), "matching supported start");
                        evolveEnvironment(server, proposed);
                        send(server, target);
                        String context = " surface=" + surface + " angle=" + angle;
                        check(selected.down().distanceTo(runtime(server).geometryReferenceFrame().down()) < 1e-12
                                && selected.left().distanceTo(runtime(server).geometryReferenceFrame().left()) < 1e-12,
                                "selected exact frame parity" + context);
                        near(target, server.position(), "Vanilla accepts SELF endpoint" + context);
                        // The capsule CCD stops at its existing contact skin; Vanilla
                        // independently accepts and installs the exact packet endpoint.
                        check(target.distanceTo(server.resolvedCollisionP) <= cc.sighs.gravityengine.gravity.collision.CollisionTolerances.CONTACT_SKIN,
                                "one PLAYER solve stays within capsule contact skin" + context);

                        check(server.corrections == 0 && server.playerMoves == 1, "no correction or second solve" + context);
                        check(server.recovery.lengthSqr() == 0, "PLAYER requires no synthetic recovery" + context);
                    } finally { server.discard(); }
                }
            }
        } finally { level.setBlock(block, saved, 18); level.setBlock(adjacent, savedAdjacent, 18); }
    }

    private static cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState runtime(Actor actor) {
        return GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
    }

    private static Actor supportedActor(ServerLevel level, String name, GravityFrame frame, double x) {
        var actor = player(level, name, new Vec3(x, 302, 8.5), null);
        GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(frame.down(), .08));
        var body = GravityEntityGeometry.exactBody(actor, frame);
        actor.setPos(actor.position().add(0, 300 - body.enclosingAabb().minY(), 0));
        GravityEntityGeometry.installFromPositionAnchor(actor, frame, actor.position());
        actor.setDeltaMovement(frame.down().scale(.1));
        actor.travel(Vec3.ZERO);
        for (int settle = 0; settle < 30 && !actor.onGround(); settle++) {
            actor.setDeltaMovement(frame.down().scale(.05));
            actor.travel(Vec3.ZERO);
        }
        check(actor.onGround(), "supported fixture grounded name=" + name + " P=" + actor.position());
        return actor;
    }

    private static void evolveEnvironment(Actor actor, GravityState proposed) {
        // Fixture replaces environmental evidence while preserving the identical
        // installed body and continuity seen at outer operation entry on each side.
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var old = component.committedApplication();
        component.commitApplication(new cc.sighs.gravityengine.gravity.model.CommittedGravityApplication(
                proposed, old.effectiveSuppression(), old.plan()));
    }

    private static void stationaryAiStepPackets(ServerLevel level) {
        var block = new BlockPos(8, 299, 8); var saved = level.getBlockState(block);
        level.setBlock(block, Blocks.STONE.defaultBlockState(), 18);
        var frame = GravityFrame.fromDown(new Vec3(-.0226, -.98, -.2).normalize(), .08);
        var actor = supportedActor(level, "TickRest", frame, 8.5);
        Vec3[] requests = new Vec3[100];
        try {
            Vec3 start = actor.position(); long revision = runtime(actor).bodyShapeRevision();
            for (int tick = 0; tick < requests.length; tick++) {
                actor.tickCount++;
                // Reproduce the traced pre-aiStep normal velocity every tick.
                actor.setDeltaMovement(frame.down().scale(.09856));
                actor.aiStep();
                Vec3 local = frame.worldToLocal(actor.velocityAtTravel);
                check(Math.abs(local.x) < 1e-12 && Math.abs(local.z) < 1e-12, "zero-input aiStep tangent tick=" + tick);
                near(start, actor.position(), "aiStep supported fixed P tick=" + tick);
                check(actor.onGround() && runtime(actor).restingContactSnapshot() != null, "stable aiStep support");
                check(revision == runtime(actor).bodyShapeRevision(), "no stationary shape churn");
                requests[tick] = actor.position();
            }
        } finally { actor.discard(); }
        actor = supportedActor(level, "TickPackets", frame, 8.5);
        try {
            long revision = runtime(actor).bodyShapeRevision();
            for (Vec3 request : requests) {
                send(actor, request);
                near(request, actor.position(), "stationary packet endpoint accepted");
                check(actor.onGround(), "stationary packet grounding");
                check(revision == runtime(actor).bodyShapeRevision(), "no packet shape churn");
            }
            check(actor.playerMoves == requests.length && actor.corrections == 0, "no recurring Vanilla correction");
        } finally { actor.discard(); level.setBlock(block, saved, 18); }
    }

    private static Actor player(ServerLevel level, String name, Vec3 p, Quaterniond orientation) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        var actor = new Actor(level, profile);
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) {}
        };
        actor.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, actor,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) actor.corrections++;
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload body
                        && body.entityUuid().equals(actor.getUUID()))
                    actor.bodyReplies.add(body);
            }
        };
        actor.setPos(p);
        if (orientation != null) {
            var down = orientation.transform(new org.joml.Vector3d(0, -1, 0));
            GravityApplicationCoordinator.applyDirectAssignment(actor,
                    new GravityState(new Vec3(down.x, down.y, down.z), .08));
        }
        level.addNewPlayer(actor);
        return actor;
    }

    private static void selectFreeAttitude(Actor actor) {
        GravityApplicationCoordinator.applyDirectAssignment(actor,
                new GravityState(GravityFrame.DEFAULT.down(), .001));
        actor.setOnGround(false);
        actor.setSprinting(true);
        ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) (Object) actor).gravityengine$characterControl().clear();
        BodyAttitudeStreamEpochService.ensureServerStream(actor);
    }

    private static void independentAttitudeAndMovement(ServerLevel level, boolean forceNativeRejection) {
        var actor = player(level, "IndependentBody", new Vec3(8.5, 310, 8.5), null);
        try {
            selectFreeAttitude(actor);
            var start = actor.position();
            var box = actor.getBoundingBox();
            var q = new Quaterniond().rotationZ(.3);
            var update = new ServerboundBodyAttitudeStatePayload(
                level.dimension().location(),
                BodyAttitudeRuntime.Config.server().generation(),
                true,
                BodyAttitudeOwnership.ACTIVE,
                BodyAttitudeSuspensionReason.NONE,
                q,
                new org.joml.Quaterniond(q).rotateY(-Math.toRadians(0)).rotateX(Math.toRadians(0)),
                false,
                BodyAttitudeStreamEpochService.ensureServerStream(actor),
                1);
            check(BodyAttitudeStateReceiver.receive(actor, update), "actor update accepted independently");
            var accepted = BodyAttitudeRuntime.Access.component(actor).snapshot();
            check(accepted.state().currentWorldFromBody().equals(q, 1e-12), "Q installed before movement");
            check(actor.position().equals(start) && actor.getBoundingBox().equals(box), "Q leaves P and collider unchanged");
            actor.disturbAfterMove = forceNativeRejection;
            var target = start.add(.1, 0, 0);
            send(actor, target);
            check(actor.playerMoves == 1, "exactly one native PLAYER move");
            near(forceNativeRejection ? start : target, actor.position(), "Vanilla owns endpoint and correction");
            check(BodyAttitudeRuntime.Access.component(actor).snapshot().state().currentWorldFromBody().equals(q, 1e-12),
                    "movement acceptance or correction does not roll back independent Q");
        } finally { actor.discard(); }
    }

    private static void centerFixedBody(ServerLevel level) {
        var actor = player(level, "CenterFixedBody", new Vec3(8.5, 310, 8.5), null);
        try {
            selectFreeAttitude(actor);
            var p = actor.position(); var box = actor.getBoundingBox();
            var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
            var frame = runtime.geometryReferenceFrame();
            for (int i = 1; i <= 100; i++) {
                var q = new Quaterniond().rotationXYZ(.03*i, .01*i, .02*i);
                var update = new ServerboundBodyAttitudeStatePayload(
                level.dimension().location(),
                BodyAttitudeRuntime.Config.server().generation(),
                true,
                BodyAttitudeOwnership.ACTIVE,
                BodyAttitudeSuspensionReason.NONE,
                q,
                new org.joml.Quaterniond(q).rotateY(-Math.toRadians(i)).rotateX(Math.toRadians(0)),
                false,
                BodyAttitudeStreamEpochService.ensureServerStream(actor),
                i);
                check(BodyAttitudeStateReceiver.receive(actor, update), "independent Q installs");
                check(actor.position().equals(p) && actor.getBoundingBox().equals(box) && actor.playerMoves == 0,
                        "attitude receipt never moves P or collision body");
                check(runtime.geometryReferenceFrame() == frame, "reference owner is unchanged");
                check(BodyAttitudeRuntime.Access.component(actor).state().currentWorldFromBody().equals(q, 1e-12),
                        "free Q remains controllable");
            }
        } finally { actor.discard(); }
    }

    private static void send(Actor player, Vec3 target) {
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(target.x, target.y, target.z, false));
    }

    private static final class Actor extends ServerPlayer {
        Vec3 velocityAtTravel;
        @Override public void travel(Vec3 input) {
            velocityAtTravel = getDeltaMovement();
            super.travel(input);
        }
        int playerMoves;
        int corrections;
        Vec3 moveStart;
        Vec3 resolvedCollisionP;
        Vec3 recovery = Vec3.ZERO;
        cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody oldMoveBody;
        boolean disturbAfterMove;
        final java.util.List<cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload> bodyReplies = new java.util.ArrayList<>();
        Actor(ServerLevel level, GameProfile profile) {
            super(level.getServer(), level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        }
        @Override public void setOnGroundWithMovement(boolean grounded, Vec3 movement) {
            super.setOnGroundWithMovement(grounded, movement);
            var result = runtime(this).currentMoveResult();
            if (result != null) {
                recovery = new Vec3(result.recoveryMovement().x(), result.recoveryMovement().y(), result.recoveryMovement().z());
                // Vanilla Entity.move skips its P write for |resolved|^2 <= 1e-7.
                // handleMovePlayer still commits accepted requested P. Compare
                // the collision endpoint here and the actual accepted P above.
                resolvedCollisionP = moveStart.add(result.resolvedMovement().x(),
                        result.resolvedMovement().y(), result.resolvedMovement().z());
            }
        }
        @Override public void move(MoverType type, Vec3 movement) {
            if (type == MoverType.PLAYER) playerMoves++;
            oldMoveBody = VanillaBodyOccupancy.capture(this);
            moveStart = position();
            super.move(type, movement);
            if (type==MoverType.PLAYER && disturbAfterMove) setPos(position().add(1,0,0));
        }
    }

    private static void near(Vec3 expected, Vec3 actual, String message) {
        check(expected.distanceTo(actual) <= 1e-7, message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
