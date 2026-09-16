package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.UUID;

/** Runs only with -PcontrolBoundaryChecks in an isolated development server. */
@Mod("gravityengine_control_tests")
public final class ControlBoundaryChecks {
    private static int assertions;
    private static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK, "gravityengine_control_tests");
    private static final java.util.function.Supplier<RecordingBlock> CALLBACK_BLOCK = BLOCKS.register("callback", RecordingBlock::new);

    public ControlBoundaryChecks(net.neoforged.bus.api.IEventBus bus) {
        BLOCKS.register(bus);
        NeoForge.EVENT_BUS.addListener(ControlBoundaryChecks::started);
        NeoForge.EVENT_BUS.addListener(ControlBoundaryChecks::jumped);
    }

    private static void jumped(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof CheckPlayer player) player.jumpEvents++;
    }

    private static void started(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        try {
            var level = event.getServer().overworld();
            level.getChunk(0, 0);
            for (Vec3 down : new Vec3[]{new Vec3(0, -1, 0), new Vec3(1, 0, 0),
                    new Vec3(0, 1, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1),
                    new Vec3(0, 0, -1), new Vec3(1, -2, .5).normalize()}) {
                parity(level, down, null, false);
                parity(level, down, MobEffects.LEVITATION, false);
                parity(level, down, MobEffects.SLOW_FALLING, false);
                parity(level, down, null, true);
                sneakEdge(level, down);
                jump(level, down);
                elytra(level, down);
                nativeBounce(level, down);
                blockSpeed(level, down);
                sustainedWall(level, down);
            }
            for (Vec3 down : new Vec3[]{new Vec3(0, -1, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)}) {
                supportFriction(level, down, Blocks.STONE.defaultBlockState());
                supportFriction(level, down, Blocks.ICE.defaultBlockState());
            }
            releasedInputUsesGroundFriction(level);
            normalTickLifecycle(level);
            unsupportedDimensionsLifecycle(level);
            insideBlockOccupancy(level);
            callbackDiscontinuity(level);
            nativeMoveBranches(level);
            callbackNestedMove(level);
            nativeCallbackContract(level);
            packetOwnership(level);
            GeometryAuthorityChecks.run(level);
            PersistenceLifecycleChecks.run(level);
            System.out.println("CONTROL_BOUNDARY_CHECKS_PASSED assertions=" + assertions);
            java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"), "PASS " + assertions);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.out.println("CONTROL_BOUNDARY_CHECKS_FAILED");
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"), "FAIL " + failure);
            } catch (java.io.IOException writeFailure) { failure.addSuppressed(writeFailure); }
        } finally {
            event.getServer().halt(false);
        }
    }

    private static CheckPlayer player(ServerLevel level, GravityFrame frame) {
        // Dinnerbone is an existing Vanilla-ownership exclusion. It changes
        // presentation only, leaving native movement policy and dimensions
        // identical while FIELD gravity remains enabled for the custom actor.
        CheckPlayer player = new CheckPlayer(level, frame == null ? "Dinnerbone" : "ControlBoundary");
        player.setPos(8, 300, 8);
        if (frame != null) {
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(player,
                    new GravityState(frame.down(), frame.strength()));
        }
        return player;
    }

    private static void sneakEdge(ServerLevel level, Vec3 down) {
        var frame = GravityFrame.fromDown(down, .08);
        var player = player(level, frame);
        player.setShiftKeyDown(true);
        player.setOnGround(true);
        Vec3 request = frame.localToWorld(new Vec3(.06, -.1, .06));
        if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.requiresReferenceGeometry(frame)) {
            // No GravityEngine scene exists. A custom probe here would fail; this
            // executes Vanilla's original policy and empty-world decrement loops.
            near(new Vec3(0,-.1,0), player.edge(request, MoverType.SELF), "native default sneak");
        }
        try (var operation = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(
                player, cc.sighs.gravityengine.gravity.model.GravityOperationType.MOVE, player.position())) {
            var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
            runtime.beginMovement(cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.collisionRoute(player));
            for (var mover : MoverType.values()) {
                near(mover == MoverType.SELF || mover == MoverType.PLAYER
                                ? operation.frame().up().scale(-.1) : request,
                        player.edge(request, mover), "native mover gate " + mover + " down=" + down);
            }
            player.getAbilities().flying = true;
            near(request, player.edge(request, MoverType.SELF), "native flying sneak gate");
            player.getAbilities().flying = false;
            var upward = operation.frame().localToWorld(new Vec3(.06,1e-12,.06));
            near(upward, player.edge(upward, MoverType.SELF), "native strict upward gate");
            player.setShiftKeyDown(false);
            near(request, player.edge(request, MoverType.SELF), "native semantic sneak gate");
            player.setShiftKeyDown(true);
            player.setOnGround(false);
            player.fallDistance = .1f;
            near(request, player.edge(request, MoverType.SELF), "native absent near-ground support");
        }
    }

    private static void parity(ServerLevel level, Vec3 down, Holder<MobEffect> effect, boolean noGravity) {
        GravityFrame frame = GravityFrame.fromDown(down, .08);
        CheckPlayer vanilla = player(level, null);
        CheckPlayer custom = player(level, frame);
        // A translated custom operation freezes look; default-reference travel
        // retains the native helper's temporal ownership and receives identical inputs.
        custom.changeLookInHelper = cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.requiresReferenceGeometry(frame);
        Vec3 initial = new Vec3(.12, -.23, .31);
        vanilla.setDeltaMovement(initial);
        custom.setDeltaMovement(frame.localToWorld(initial));
        vanilla.setNoGravity(noGravity);
        custom.setNoGravity(noGravity);
        if (effect != null) {
            vanilla.addEffect(new MobEffectInstance(effect, 100, 1));
            custom.addEffect(new MobEffectInstance(effect, 100, 1));
        }
        Vec3 input = new Vec3(.3F * .98F, 0, .8F * .98F);
        Vec3 vanillaBefore = vanilla.position();
        Vec3 customBefore = custom.position();
        vanilla.xo = vanillaBefore.x; vanilla.yo = vanillaBefore.y; vanilla.zo = vanillaBefore.z;
        custom.xo = customBefore.x; custom.yo = customBefore.y; custom.zo = customBefore.z;
        vanilla.travel(input);
        custom.travel(input);
        check(!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomLocomotion(vanilla),
                "comparison actor is Vanilla-owned");
        near(frame.localToWorld(vanilla.getDeltaMovement()), custom.getDeltaMovement(), "travel velocity " + down + " " + effect);
        near(frame.localToWorld(vanilla.position().subtract(vanillaBefore)),
                custom.position().subtract(customBefore), "travel displacement " + down);
        check(custom.relativeCalls == 1 && custom.moveCalls == 1, "one native movement helper/move");
        check(!GravityEntityAccess.cast(custom).gravityengine$gravityComponent().runtime().isInMove(), "travel scope closes");
        check(Math.abs(custom.moveDist - vanilla.moveDist) < 1e-6, "native movement emission distance " + down);
        check(Math.abs(custom.walkDist - vanilla.walkDist) < 1e-6, "native walk distance " + down);
        check(Math.abs(custom.walkAnimation.speed() - vanilla.walkAnimation.speed()) < 1e-6,
                "native animation consumes reference tangent distance " + down);
    }

    private static void jump(ServerLevel level, Vec3 down) {
        GravityFrame frame = GravityFrame.fromDown(down, .08);
        CheckPlayer player = player(level, frame);
        player.setDeltaMovement(frame.localToWorld(new Vec3(.2, 2, -.3)));
        player.jumpFromGround();
        Vec3 result = frame.worldToLocal(player.getDeltaMovement());
        near(new Vec3(.2, GravityLivingAccess.cast(player).gravityengine$getJumpPower(), -.3), result,
                "native jump replaces upward velocity");
        check(player.jumpEvents == 1, "native accepted jump callback once");
        player.power = 0;
        Vec3 before = player.getDeltaMovement();
        player.jumpFromGround();
        near(before, player.getDeltaMovement(), "zero jump power keeps velocity");
        check(player.jumpEvents == 1, "native zero-power gate suppresses callback");

        player.power = .42F;
        player.setSprinting(true);
        player.setYRot(37);
        player.setDeltaMovement(Vec3.ZERO);
        player.jumpFromGround();
        Vec3 sprint = player.getDeltaMovement().subtract(frame.up().scale(player.power));
        check(Math.abs(sprint.dot(frame.up())) < 1.0E-7, "sprint jump is reference tangent");
        check(Math.abs(sprint.length() - .2) < 1.0E-5, "native sprint impulse magnitude");
        var look = cc.sighs.gravityengine.look.PlayerLookIntegration.capture(player, frame);
        var heading = cc.sighs.gravityengine.gravity.movement.GravityPhysics.tangentForward(
                look.forward(), frame, look.zeroPitchForward());
        check(sprint.normalize().dot(heading) > .999999, "sprint follows semantic view");
        check(player.jumpEvents == 2, "native sprint jump callback once");
    }

    private static void supportFriction(ServerLevel level, Vec3 down, BlockState material) {
        GravityFrame frame = GravityFrame.fromDown(down, .08);
        Vec3 center = new Vec3(8.5, 299.5, 8.5);
        var original = new java.util.HashMap<BlockPos, BlockState>();
        try {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = BlockPos.containing(center.add(frame.localToWorld(new Vec3(x, 0, z))));
                    original.put(pos, level.getBlockState(pos));
                    level.setBlock(pos, material, 18);
                }
            }
            var player = player(level, frame);
            Vec3 feet = center.subtract(down.scale(.55));
            player.setPos(feet.subtract(down.scale(player.getBbHeight() / 2.0))
                    .subtract(0, player.getBbHeight() / 2.0, 0));
            player.setDeltaMovement(down.scale(.1));
            player.travel(Vec3.ZERO);
            check(player.onGround(), "native move commits non-Y grounding " + down);
            check(GravityEntityAccess.cast(player).gravityengine$getVanillaSupportingBlock().isPresent(),
                    "native move publishes exact support material " + down);
            player.setDeltaMovement(frame.localToWorld(new Vec3(.12, -.01, .2)));
            Vec3 beforeFrictionMove = player.position();
            player.travel(Vec3.ZERO);
            double friction = material.getBlock().getFriction() * .91F;
            Vec3 local = frame.worldToLocal(player.getDeltaMovement());
            near(new Vec3(.12 * friction, down.equals(GravityState.DEFAULT_DOWN) ? -.08*.98F : 0, .2 * friction), local,
                    "native friction consumes captured support " + down + " " + material
                            + " before=" + beforeFrictionMove + " after=" + player.position()
                            + " result=" + player.fallResult);
        } finally {
            original.forEach((pos, state) -> level.setBlock(pos, state, 18));
        }
    }

    /** Executes production LivingEntity.travel, Entity.move and Mixins, including
     * the pre-move onGround friction selection missed by static rest fixtures. */
    private static void releasedInputUsesGroundFriction(ServerLevel level) {
        var original = new java.util.HashMap<BlockPos, BlockState>();
        try {
            for (int x = 5; x <= 11; x++) for (int z = 5; z <= 11; z++) {
                var pos = new BlockPos(x, 299, z);
                original.put(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
            }
            for (Vec3 down : new Vec3[]{new Vec3(0,-.8,-.6), new Vec3(0,-.99367,-.11237).normalize()}) {
                var frame = GravityFrame.fromDown(down, .08);
                var actor = player(level, frame);
                var geometry = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.characterBodyAtCenter(
                        actor.getBbWidth(), actor.getBbHeight(), Vec3.ZERO, frame.up());
                double radius = geometry.enclosingAabb().maxY();
                actor.setPos(8.5, 300 + radius + .02 - actor.getBbHeight()/2, 8.5);
                actor.setDeltaMovement(down.scale(.1));
                actor.travel(Vec3.ZERO);
                check(actor.onGround(), "released-input fixture establishes stable oblique support " + down);
                actor.setDeltaMovement(new Vec3(.18,0,0));
                double expected = .18;
                double damping = .6F * .91F;
                for (int tick = 0; tick < 12; tick++) {
                    actor.travel(Vec3.ZERO);
                    expected *= damping;
                    check(Math.abs(expected - actor.getDeltaMovement().x) < 1e-7, "released-input ground damping tick=" + tick + " " + down + " expected=" + expected + " actual=" + actor.getDeltaMovement());
                    check(actor.onGround(), "tangential inertia cannot lose native ground tick=" + tick);
                    var move = actor.fallResult;
                    check(move != null && move.gameplayGrounded() && move.terminalGrounded(),
                            "real finite oblique support remains stable tick=" + tick);
                    check(actor.getDeltaMovement().x > 0, "Vanilla travel inertia decays without forced zero");
                }
            }
            double upDot = .014732121;
            var steep = GravityFrame.fromDown(new Vec3(-Math.sqrt(1-upDot*upDot),-upDot,0), .08);
            var actor = player(level, steep);
            var body = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.characterBodyAtCenter(
                    actor.getBbWidth(), actor.getBbHeight(), Vec3.ZERO, steep.up());
            actor.setPos(8.5,300+body.enclosingAabb().maxY()-actor.getBbHeight()/2,8.5);
            Vec3 start = actor.position();
            for (int tick=0; tick<12; tick++) {
                actor.tickCount++;
                actor.aiStep();
                check(!actor.onGround(), "steep normal response never acquires native ground");
                check(actor.getDeltaMovement().x < -.03, "native fall response retains surface tangent velocity");
            }
            check(actor.position().distanceTo(start) > .2, "real aiStep and block response slide on near-vertical physical support");
        } finally {
            original.forEach((pos, state) -> level.setBlock(pos, state, 18));
        }
    }

    private static void normalTickLifecycle(ServerLevel level) {
        CheckPlayer player = player(level, GravityFrame.DEFAULT);
        player.xxa = .7F;
        player.zza = .4F;
        player.setOnGround(true);
        GravityLivingAccess.cast(player).gravityengine$setJumping(true);
        player.aiStep();
        check(player.jumpEvents == 1, "live jump callback");
        check(GravityLivingAccess.cast(player).gravityengine$getJumpDelay() == 10, "native jump delay");
        int ai = player.aiCalls;
        int pushes = player.pushCalls;
        int moves = player.moveCalls;
        player.tickCount++;
        player.setOnGround(true);
        player.xxa = .7F;
        player.zza = .4F;
        player.aiStep();
        check(GravityLivingAccess.cast(player).gravityengine$getJumpDelay() == 9, "next normal tick uses native cooldown");
        check(player.xxa == .7F * .98F && player.zza == .4F * .98F, "native input attenuation once");
        check(player.aiCalls == ai + 1 && player.pushCalls == pushes + 1, "normal tick retains AI and push lifecycle");
        check(player.moveCalls == moves + 1, "normal tick moves once");
        check(player.jumpEvents == 1, "cooldown prevents another jump");
        GravityLivingAccess.cast(player).gravityengine$setJumpDelay(0);
        player.setOnGround(true);
        player.tickCount++;
        player.aiStep();
        check(GravityLivingAccess.cast(player).gravityengine$getJumpDelay() == 10, "normal tick can jump after cooldown");
        check(player.jumpEvents == 2, "each accepted jump emits its gameplay event");
    }

    private static void elytra(ServerLevel level, Vec3 down) {
        var frame = GravityFrame.fromDown(down, .08);
        var player = player(level, frame);
        player.beginElytra();
        player.setDeltaMovement(frame.localToWorld(new Vec3(.12, -.23, .31)));
        player.fallDistance = 4;
        Vec3 expected = cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics.step(
                player.getDeltaMovement(), cc.sighs.gravityengine.look.PlayerLookIntegration.capture(player, frame).forward(),
                frame.up(), down.scale(.08), false);
        Vec3 before = player.position();
        player.travel(Vec3.ZERO);
        near(expected, player.getDeltaMovement(), "native Elytra commits custom aerodynamics");
        near(expected, player.position().subtract(before), "Elytra moves exactly once");
        check(player.moveCalls == 1 && player.relativeCalls == 0, "native Elytra branch stays aerodynamic");
        check(player.fallDistance <= 1.5, "native slow-fall cap reads reference vertical");
    }

    private static void packetOwnership(ServerLevel level) throws Exception {
        var profile = new GameProfile(UUID.randomUUID(), "PacketBoundary");
        var player = new CheckServerPlayer(level, profile);
        var sent = new java.util.ArrayList<net.minecraft.network.protocol.Packet<?>>();
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,
                    net.minecraft.network.PacketSendListener listener) {}
        };
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(), connection,
                player, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { sent.add(packet); }
        };
        player.setPos(8, 300, 8);
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(player,
                new GravityState(new Vec3(1, 0, 0), .001));
        player.setSprinting(true);
        var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);
        var config = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.server().generation();
        var q = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(
                GravityFrame.fromDown(new Vec3(1, 0, 0), .08).orientation());
        Vec3 before = player.position();
        int tick = player.tickCount;
        var first = new cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload(level.dimension().location(),
                config,
                true,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.ACTIVE,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason.NONE,
                q,
                new org.joml.Quaterniond(q).rotateY(-Math.toRadians(10)).rotateX(Math.toRadians(20)),
                false,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeStreamEpochService.ensureServerStream(player),
                1);
        var second = new cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload(level.dimension().location(),
                config,
                true,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.ACTIVE,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason.NONE,
                q,
                new org.joml.Quaterniond(q).rotateY(-Math.toRadians(30)).rotateX(Math.toRadians(40)),
                false,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeStreamEpochService.ensureServerStream(player),
                2);
        check(cc.sighs.gravityengine.network.BodyAttitudeStateReceiver.receive(player, first), "current body A accepted");
        check(cc.sighs.gravityengine.network.BodyAttitudeStateReceiver.receive(player, second), "current body B accepted");
        check(component.view().localYaw() == 30 && component.view().localPitch() == 40,
                "B replaces A immediately without a historical input tick");
        check(player.tickCount == tick && player.position().equals(before), "body receipt cannot simulate translation or tick");
        var walkStat = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.SPRINT_ONE_CM);
        int previousWalk = player.getStats().getValue(walkStat);
        player.setOnGround(true);
        Vec3 tangentWalk = GravityFrame.fromDown(new Vec3(1, 0, 0), .08).localToWorld(new Vec3(1, 0, 0));
        player.checkMovementStatistics(tangentWalk.x, tangentWalk.y, tangentWalk.z);
        check(player.getStats().getValue(walkStat) == previousWalk + 100, "native sprint statistics count non-Y tangent travel");
        player.setOnGround(false);
        long lastBodySequence = attitudeOverlap(level, player, q, config, sent, second.stateSequence());
        level.addNewPlayer(player);
        var packet = new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
                before.x, before.y, before.z + .1, true);
        player.connection.handleMovePlayer(packet);
        check(player.validationMoves == 1, "one production move for the accepted packet");
        near(before.add(0, 0, .1), player.position(), "Vanilla position packet owns translation under non-Y gravity");
        check(!player.onGround(), "conflicting packet ground cannot invent support in empty space");
        Vec3 acceptedPosition = player.position();
        var obstacle = new BlockPos(9, 300, 8);
        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 18);
        // A blocked packet endpoint stays Vanilla's decision. Exactly one
        // production move runs and the committed position is either Vanilla's
        // accepted packet endpoint or Vanilla's own rejection correction back
        // to the pre-move P. GravityEngine contributes no third outcome.
        var blockedTarget = new Vec3(10, 300, acceptedPosition.z);
        player.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
                blockedTarget.x, blockedTarget.y, blockedTarget.z, false));
        boolean blockedAccepted = player.position().distanceTo(blockedTarget) <= .07;
        boolean blockedCorrected = player.position().distanceTo(acceptedPosition) <= 1.0E-6;
        check(blockedAccepted || blockedCorrected, "blocked packet commits a Vanilla outcome only");
        check(player.validationMoves == 2, "one production move for the blocked packet; no second packet solve");
        check(GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime()
                        .lastCommittedMovementGroundContinuity(level.getGameTime()).isEmpty()
                        || blockedAccepted,
                "a Vanilla correction invalidates the unaccepted move's ground continuity");
        var teleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
        teleport.setAccessible(true);
        var awaiting = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
        awaiting.setAccessible(true);
        if (blockedCorrected) {
            check(teleport.getInt(player.connection) > 0, "Vanilla rejection issues its teleport id");
            player.connection.handleAcceptTeleportPacket(
                    new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(
                            teleport.getInt(player.connection)));
            check(awaiting.get(player.connection) == null, "Vanilla confirmation completes the correction");
            check(player.fallDistance == 0, "rejected trial movement contributes no fall distance");
        }
        double nearWallX = player.getX() + obstacle.getX() - player.getBoundingBox().maxX + .0005;
        Vec3 requestedNearWall = new Vec3(nearWallX, player.getY(), player.getZ());
        player.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
                requestedNearWall.x, requestedNearWall.y, requestedNearWall.z, true));
        check(player.validationMoves == 3, "near-wall packet runs exactly one production move");
        boolean nearWallAccepted = player.position().distanceTo(requestedNearWall) <= .07;
        boolean nearWallCorrected = player.position().distanceTo(acceptedPosition) <= 1.0E-6;
        check(nearWallAccepted || nearWallCorrected,
                "near-wall packet commits Vanilla's endpoint or Vanilla's correction, never a resolved substitute");
        if (nearWallCorrected) {
            player.connection.handleAcceptTeleportPacket(
                    new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(
                            teleport.getInt(player.connection)));
            check(awaiting.get(player.connection) == null,
                    "near-wall Vanilla correction acknowledgement completes");
        }
        level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 18);
        player.noPhysics = true;
        Vec3 noPhysicsTarget = player.position().add(.02, .03, .04);
        player.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
                noPhysicsTarget.x, noPhysicsTarget.y, noPhysicsTarget.z, true));
        near(noPhysicsTarget, player.position(), "noPhysics packet retains Vanilla position authority");
        check(player.validationMoves == 4 && player.onGround(), "noPhysics retains native packet ground without custom solve");
        check(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.geometryMatchesFrame(player,
                GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime().geometryReferenceFrame()),
                "noPhysics packet repairs exact proxy at committed P");
        player.noPhysics = false;
        Vec3 packetPosition = player.position();
        player.getAbilities().flying = true;
        int beforeBodyReceive = sent.size();
        check(cc.sighs.gravityengine.network.BodyAttitudeStateReceiver.receive(player, second.forTransport(++lastBodySequence)),
                "server stores client-produced body despite its own flight eligibility");
        check(component.ownership() == cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.ACTIVE,
                "server does not reconstruct client control eligibility");
        check(sent.size() == beforeBodyReceive, "body receipt sends no correction to the owner");
        var released = new cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload(level.dimension().location(),
                config,
                true,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.INACTIVE,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason.CONTROLLED_FLIGHT,
                q,
                new org.joml.Quaterniond(q).rotateY(-Math.toRadians(30)).rotateX(Math.toRadians(40)),
                false,
                second.streamEpoch(),
                ++lastBodySequence);
        check(cc.sighs.gravityengine.network.BodyAttitudeStateReceiver.receive(player, released), "client release is installed");
        check(component.ownership() == cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.INACTIVE,
                "latest client state owns attitude release");
        check(GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime().geometryReferenceFrame() != null,
                "actor suspension leaves reference geometry owned by gravity");
        near(packetPosition, player.position(), "body suspension never changes Vanilla-owned feet");
        check(player.tickCount == tick, "state acceptance and suspension execute no additional actor tick");
    }

    private static void nativeMoveBranches(ServerLevel level) {
        for (var frame : new GravityFrame[]{GravityFrame.DEFAULT,
                GravityFrame.fromDown(new Vec3(1, 0, 0), .08),
                GravityFrame.fromDown(new Vec3(0, 1, 0), .08),
                GravityFrame.fromDown(new Vec3(1, -2, .5).normalize(), .08)}) {
            for (double length : new double[]{0, .0003, .0004}) {
                var actor = player(level, frame);
                Vec3 start = actor.position();
                Vec3 request = frame.localToWorld(new Vec3(0, -length, 0));
                actor.move(MoverType.SELF, request);
                check(actor.fallCalls == 1 && (frame == GravityFrame.DEFAULT || actor.fallResult != null),
                        "ordinary move solves and runs fall even below position threshold");
                if (actor.fallResult != null) near(request, cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter.toMinecraft(
                        actor.fallResult.resolvedMovement()), "solver keeps the small displacement");
                near(length > .00031623 ? start.add(request) : start, actor.position(),
                        "native position threshold is independent of solver displacement");
                check(actor.fallResult == null || actor.fallResult.supportBlock().isEmpty(), "empty small endpoint has no support");
                var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
                check(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.geometryMatchesFrame(
                        actor, runtime.geometryReferenceFrame()), "small move proxy follows actual P");
            }
            for (boolean noPhysics : new boolean[]{false, true}) {
                var actor = player(level, frame);
                actor.noPhysics = noPhysics;
                actor.setOnGround(true);
                Vec3 start = actor.position();
                Vec3 request = noPhysics ? new Vec3(.1, .2, .3) : Vec3.ZERO;
                actor.move(noPhysics ? MoverType.SELF : MoverType.PISTON, request);
                near(start.add(request), actor.position(), "native early-return translation");
                check(actor.fallCalls == 0 && actor.onGround(), "early return preserves native ground/fall");
                check(!GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().isInMove(),
                        "early return closes operation");
                var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
                check(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.geometryMatchesFrame(
                        actor, runtime.geometryReferenceFrame()), "early return proxy follows actual P");
            }
        }
    }

    private static void callbackNestedMove(ServerLevel level) {
        var actor = player(level, GravityFrame.DEFAULT);
        actor.callbackMovement = new Vec3(.02, 0, 0);
        actor.move(MoverType.SELF, new Vec3(0, -.1, 0));
        check(actor.fallCalls == 2, "nested native move executes its own fall callback");
        check(actor.parentResultRestored, "callback move cannot replace parent collision result");
        check(!GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().isInMove(),
                "nested native move closes both scopes");
    }

    /** Calls the real native callback sites, including their gates and exceptional unwind. */
    private static void nativeCallbackContract(ServerLevel level) {
        var block = CALLBACK_BLOCK.get();
        var pos = new BlockPos(8, 299, 8);
        var previous = level.getBlockState(pos);
        try {
            level.setBlock(pos, block.defaultBlockState(), 18);
            for (boolean throwing : new boolean[]{false, true}) {
                block.calls.clear();
                block.throwing = throwing;
                block.outputVelocity = new Vec3(.2, .3, .4);
                var actor = player(level, GravityFrame.DEFAULT);
                actor.setPos(8.5, 300.2, 8.5);
                var impact = new Vec3(.1, -.5, .1);
                actor.setDeltaMovement(impact);
                RuntimeException caught = null;
                try { actor.move(MoverType.SELF, impact); }
                catch (RuntimeException failure) { caught = failure; }
                check(caught == (throwing ? block.failure : null), "native callback exception propagates unchanged");
                near(impact, block.seenVelocity, "native callback sees world-space impact velocity");
                check(block.calls.equals(throwing ? java.util.List.of("fall") : java.util.List.of("fall", "step")),
                        "native fall-on runs once before step-on; exception stops subsequent callbacks");
                near(new Vec3(.2, .3, .4), actor.getDeltaMovement(), "callback world velocity survives response/unwind");
                check(!GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().isInMove(),
                        "throwing material callback leaves no movement scope");
            }
            for (boolean throwing : new boolean[]{false, true}) {
                block.calls.clear();
                block.throwing = throwing;
                block.outputVelocity = new Vec3(-.2, .3, .4);
                var actor = new net.minecraft.world.entity.item.ItemEntity(level, 7.7, 299.5, 8.5,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(
                        actor, new GravityState(new Vec3(1, 0, 0), .08));
                var impact = new Vec3(.5, .1, .1);
                actor.setDeltaMovement(impact);
                RuntimeException caught = null;
                try { actor.move(MoverType.SELF, impact); }
                catch (RuntimeException failure) { caught = failure; }
                check(caught == (throwing ? block.failure : null), "passive native callback exception propagates");
                near(impact, block.seenVelocity, "passive callback sees world impact on a non-Y surface");
                check(block.calls.equals(throwing ? java.util.List.of("fall") : java.util.List.of("fall", "step")),
                        "passive native callback order and count");
                near(block.outputVelocity, actor.getDeltaMovement(), "passive callback world velocity survives response/unwind");
                check(!GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime().isInMove(),
                        "passive exception leaves no movement scope");
            }
            block.throwing = false;
            block.calls.clear();
            var actor = player(level, GravityFrame.DEFAULT);
            actor.setPos(8.5, 302, 8.5);
            actor.move(MoverType.SELF, new Vec3(0, -.1, 0));
            check(block.calls.isEmpty(), "free movement cannot run landing block callbacks");
        } finally {
            block.throwing = false;
            level.setBlock(pos, previous, 18);
        }
    }

    private static final class RecordingBlock extends net.minecraft.world.level.block.Block {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        final RuntimeException failure = new RuntimeException("native callback test");
        Vec3 seenVelocity;
        Vec3 outputVelocity;
        boolean throwing;

        RecordingBlock() { super(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()); }

        @Override public void updateEntityAfterFallOn(net.minecraft.world.level.BlockGetter level,
                                                     net.minecraft.world.entity.Entity entity) {
            calls.add("fall");
            seenVelocity = entity.getDeltaMovement();
            entity.setDeltaMovement(outputVelocity);
            if (throwing) throw failure;
        }

        @Override public void stepOn(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                                     net.minecraft.world.entity.Entity entity) {
            calls.add("step");
        }
    }

    private static void callbackDiscontinuity(ServerLevel level) {
        for (boolean travel : new boolean[]{false, true}) {
            var player = player(level, GravityFrame.fromDown(new Vec3(1,0,0),.08));
            var destination = player.position().add(4,2,3);
            player.callbackDestination = destination;
            var velocity = new Vec3(.123,.234,.345);
            player.callbackVelocity = velocity;
            if (travel) player.travel(new Vec3(.1,0,.2));
            else player.move(MoverType.SELF, new Vec3(.1,-.1,.2));
            check(player.callbackCompleted, "teleporting fall callback completes normally");
            near(destination, player.position(), "discontinuity wins over old movement endpoint");
            near(velocity, player.getDeltaMovement(), "old movement/travel cannot overwrite callback velocity");
            var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
            check(!runtime.isInMove(), "superseded scopes unwind");
            check(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.geometryMatchesFrame(
                    player, runtime.geometryReferenceFrame()), "destination exact geometry rebuilt after unwind");
            check(!player.onGround(), "discontinuity cannot publish trial support");
        }
    }

    private static void nativeBounce(ServerLevel level, Vec3 down) {
        var frame = GravityFrame.fromDown(down,.08);
        var stepping = player(level, frame);
        for (double vertical : new double[]{.05, .2}) {
            stepping.setDeltaMovement(frame.localToWorld(new Vec3(.2, vertical, .3)));
            Blocks.SLIME_BLOCK.stepOn(level, stepping.blockPosition(), Blocks.SLIME_BLOCK.defaultBlockState(), stepping);
            double factor = vertical < .1 ? .4 + vertical * .2 : 1;
            near(frame.localToWorld(new Vec3(.2 * factor, vertical, .3 * factor)), stepping.getDeltaMovement(),
                    "native slime tangent damping " + down + " " + vertical);
        }
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.RED_BED}) {
            var player = player(level, frame);
            Vec3 incoming = frame.localToWorld(new Vec3(.2,-1,.3));
            player.setDeltaMovement(incoming);
            block.updateEntityAfterFallOn(level, player);
            double rebound = block == Blocks.SLIME_BLOCK ? 1 : block == Blocks.RED_BED ? (double).66F : 0;
            near(frame.localToWorld(new Vec3(.2,rebound,.3)), player.getDeltaMovement(),
                    "native material response with world-space live velocity " + down + " " + block);
        }
        // Full Entity.move landing proves impact velocity survives until bounce.
        if (down.equals(new Vec3(0,-1,0))) {
            var pos = new BlockPos(8,299,8);
            var old = level.getBlockState(pos);
            try {
                level.setBlock(pos, Blocks.SLIME_BLOCK.defaultBlockState(),18);
                var player = player(level,frame);
                player.setPos(8.5,300.2,8.5);
                player.setDeltaMovement(0,-.5,0);
                player.move(MoverType.SELF,player.getDeltaMovement());
                check(player.getDeltaMovement().y > .49, "native move preserves slime impact and outward bounce");
            } finally { level.setBlock(pos,old,18); }
        }
    }

    private static void blockSpeed(ServerLevel level, Vec3 down) {
        var frame = GravityFrame.fromDown(down, .08);
        var actor = player(level, frame);
        var current = actor.blockPosition();
        var support = current.below();
        var oldCurrent = level.getBlockState(current);
        var oldSupport = level.getBlockState(support);
        try {
            level.setBlock(support, Blocks.SOUL_SAND.defaultBlockState(), 18);
            for (var cell : new net.minecraft.world.level.block.Block[]{Blocks.AIR, Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.HONEY_BLOCK}) {
                level.setBlock(current, cell.defaultBlockState(), 18);
                try (var operation = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(actor,
                        cc.sighs.gravityengine.gravity.model.GravityOperationType.MOVE,
                        cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.installedBodyCenter(actor),
                        1.0, cc.sighs.gravityengine.gravity.integration.GravityOperation.defaultDomain(actor))) {
                    GravityEntityAccess.cast(actor).gravityengine$setVanillaSupportingBlock(support);
                    float expected = cell == Blocks.AIR ? Blocks.SOUL_SAND.getSpeedFactor() : cell.getSpeedFactor();
                    check(actor.speedFactor() == expected, "native current/support speed precedence " + down + " " + cell);
                    GravityEntityAccess.cast(actor).gravityengine$setVanillaSupportingBlock(null);
                    check(actor.speedFactor() == (down.equals(GravityState.DEFAULT_DOWN) ? expected : cell.getSpeedFactor()),
                            "support lookup follows native/default or exact/custom authority " + down + " " + cell);
                }
            }
        } finally {
            level.setBlock(current, oldCurrent, 18);
            level.setBlock(support, oldSupport, 18);
        }
    }

    /** Endpoint continuity through native Entity.move, including contact response and emission. */
    private static void sustainedWall(ServerLevel level, Vec3 down) {
        var saved = new java.util.HashMap<BlockPos, BlockState>();
        try {
            for (int y = 298; y <= 303; y++) for (int z = 5; z <= 17; z++) {
                var pos = new BlockPos(10, y, z);
                saved.put(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
            }
            var actor = player(level, GravityFrame.fromDown(down, .08));
            for (int tick = 0; tick < 200; tick++) {
                actor.tickCount++;
                Vec3 request = new Vec3(.15, 0, .03);
                actor.setDeltaMovement(request);
                actor.move(MoverType.SELF, request);
                var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().runtime();
                check(!runtime.isInMove() && actor.getBoundingBox().maxX <= 10.000001,
                        "native 200-tick wall closure " + down + " tick=" + tick
                                + " position=" + actor.position() + " proxy=" + actor.getBoundingBox());
            }
            check(actor.getZ() > 9, "native sustained wall slide keeps tangent progress " + down);
        } finally { saved.forEach((pos, state) -> level.setBlock(pos, state, 18)); }
    }

    private static void insideBlockOccupancy(ServerLevel level) {
        var actor = player(level, GravityFrame.fromDown(new Vec3(1,-2,.5), .08));
        BlockPos emptyCorner = null;
        for (double offset : new double[]{.15, .4, .65, .9}) {
            actor.setPos(8 + offset, 300 + offset, 8 + offset);
            var body = cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors.captureBody(actor);
            emptyCorner = BlockPos.betweenClosedStream(actor.getBoundingBox().deflate(.001))
                    .filter(pos -> !cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors.occupiesCell(body, pos))
                    .map(BlockPos::immutable).findFirst().orElse(null);
            if (emptyCorner != null) break;
        }
        check(emptyCorner != null, "fixture has an empty exact-body proxy corner");
        var saved = level.getBlockState(emptyCorner);
        try {
            level.setBlock(emptyCorner, Blocks.COBWEB.defaultBlockState(), 18);
            actor.insideBlocks();
            near(Vec3.ZERO, actor.stuckMultiplier(), "native entityInside skips empty exact-body proxy corner");
        } finally { level.setBlock(emptyCorner, saved, 18); }
        var occupied = BlockPos.containing(actor.getBoundingBox().getCenter());
        saved = level.getBlockState(occupied);
        try {
            level.setBlock(occupied, Blocks.COBWEB.defaultBlockState(), 18);
            actor.insideBlocks();
            check(actor.stuckMultiplier().lengthSqr() > 0, "native entityInside retains actual body occupancy");
        } finally { level.setBlock(occupied, saved, 18); }
    }

    private static final class CheckServerPlayer extends net.minecraft.server.level.ServerPlayer {
        private int validationMoves;
        CheckServerPlayer(ServerLevel level, GameProfile profile) {
            super(level.getServer(), level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        }
        @Override public void move(MoverType type, Vec3 movement) {
            validationMoves++;
            super.move(type, movement);
        }
    }

    /** Real ServerPlayer replication in an occupied scene, independent of collider legality. */
    private static long attitudeOverlap(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
            org.joml.Quaterniond q, long config, java.util.List<net.minecraft.network.protocol.Packet<?>> sent,
            long sequence) {
        BlockPos obstacle = new BlockPos(7, 300, 8);
        var saved = level.getBlockState(obstacle);
        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 18);
        try {
            var frame = ((GravityEntityAccess) player).gravityengine$gravityComponent().runtime().geometryReferenceFrame();
            var body = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.exactBody(player, frame);
            check(!cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine.noCollision(player, body),
                    "body-attitude regression begins in actual block penetration");
            var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);
            long revision = component.snapshot().authoritativeRevision();
            int packetCount = sent.size();
            Vec3 feet = player.position();
            int tick = player.tickCount;
            for (var orientation : java.util.List.of(q, new org.joml.Quaterniond(-q.x, -q.y, -q.z, -q.w))) {
                var update = new cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload(level.dimension().location(),
                config,
                true,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.ACTIVE,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason.NONE,
                orientation,
                new org.joml.Quaterniond(orientation).rotateY(-Math.toRadians(11)).rotateX(Math.toRadians(22)),
                false,
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeStreamEpochService.ensureServerStream(player),
                ++sequence);
                check(cc.sighs.gravityengine.network.BodyAttitudeStateReceiver.receive(player, update),
                        "overlapping exact/sign-equivalent no-op is accepted");
                check(component.snapshot().authoritativeRevision() == ++revision, "no-op still processes authoritative revision");
                check(component.view().localYaw() == 11 && component.view().localPitch() == 22, "no-op still processes semantic view");
                check(sent.size() == packetCount, "no-op emits no corrective authoritative payload to self");
            }
            near(feet, player.position(), "overlap/no-op does not replace movement endpoint");
            check(tick == player.tickCount, "overlap/no-op does not execute a player tick");
            return sequence;
        } finally {
            level.setBlock(obstacle, saved, 18);
        }
    }

    private static void near(Vec3 expected, Vec3 actual, String message) {
        check(expected.distanceTo(actual) < 1.0E-7, message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void unsupportedDimensionsLifecycle(ServerLevel level) {
        var player = new CheckPlayer(level, "CapsuleDimensions");
        player.setPos(8,350,8);
        GravityApplicationCoordinator.applyDirectAssignment(player, new GravityState(new Vec3(1,0,0),.08));
        var frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(player);
        var dimensions = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.dimensions(player);
        var body = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.exactBody(player,frame);
        var proxy = player.getBoundingBox();
        var p = player.position();
        var pose = player.getPose();
        float eye = player.getEyeHeight();
        player.setDeltaMovement(new Vec3(.02,.03,.04));
        var velocity = player.getDeltaMovement();
        try {
            for (boolean standalone : new boolean[]{false,true}) {
                player.wideShortPose = true;
                player.wideShortAllPoses = standalone;
                if (standalone) player.refreshDimensions();
                else player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                check(player.getPose()==pose,"unsupported pose rolls back through actual refreshDimensions");
                check(dimensions.equals(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.dimensions(player)),
                        "unsupported dimensions restore installed dimensions");
                check(player.getEyeHeight()==eye,"unsupported dimensions restore eye height");
                check(body.equals(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.exactBody(player,frame)),
                        "unsupported dimensions preserve exact capsule");
                check(proxy.equals(player.getBoundingBox()),"unsupported dimensions preserve proxy");
                check(p.equals(player.position()),"unsupported dimensions preserve Vanilla P");
                check(velocity.equals(player.getDeltaMovement()),"unsupported dimensions preserve deltaMovement");
            }
        } finally { player.discard(); }

        var spider = new net.minecraft.world.entity.monster.Spider(net.minecraft.world.entity.EntityType.SPIDER,level);
        spider.setPos(8,350,8);
        spider.setNoAi(true);
        try {
            GravityApplicationCoordinator.applyDirectAssignment(spider,new GravityState(new Vec3(1,0,0),.08));
            var component = GravityEntityAccess.cast(spider).gravityengine$gravityComponent();
            for (int tick=0;tick<20;tick++) {
                spider.tick();
                GravityApplicationCoordinator.updateBody(spider);
                check(component.appliedPlan().kind()==cc.sighs.gravityengine.gravity.model.GravityApplicationPlan.Kind.VANILLA,
                        "wide/short custom capability unavailable");
                check(!component.hasPending(),"wide/short must not retry failed capsule installation");
                check(component.runtime().geometryReferenceFrame()==null,"wide/short has no hidden custom collider");
            }
        } finally { spider.discard(); }
        System.out.println("UNSUPPORTED_DIMENSIONS_LIFECYCLE_PASSED");
    }

    private static final class CheckPlayer extends Player {
        private boolean wideShortPose, wideShortAllPoses;
        @Override public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
            return wideShortPose && (wideShortAllPoses || pose==net.minecraft.world.entity.Pose.CROUCHING)
                    ? net.minecraft.world.entity.EntityDimensions.scalable(1.4f,.9f)
                    : super.getDefaultDimensions(pose);
        }
        private Vec3 callbackMovement;
        private int fallCalls;
        private boolean parentResultRestored;
        private cc.sighs.gravityengine.gravity.collision.GravityMoveResult fallResult;
        private Vec3 callbackDestination, callbackVelocity;
        private boolean callbackCompleted;
        private boolean changeLookInHelper;
        private int relativeCalls, moveCalls, jumpEvents, aiCalls, pushCalls;
        private float power = .42F;

        CheckPlayer(ServerLevel level, String name) {
            super(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), name));
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart part) { return true; }
        @Override protected float getJumpPower() { return power; }
        float speedFactor() { return getBlockSpeedFactor(); }
        void insideBlocks() { tryCheckInsideBlocks(); }
        Vec3 edge(Vec3 movement, MoverType mover) { return maybeBackOffFromEdge(movement, mover); }
        Vec3 stuckMultiplier() { return stuckSpeedMultiplier; }
        void beginElytra() { setSharedFlag(7, true); }
        @Override protected void serverAiStep() { aiCalls++; }
        @Override protected void pushEntities() { pushCalls++; }
        @Override protected void checkFallDamage(double displacement, boolean grounded, BlockState state, BlockPos pos) {
            fallCalls++;
            var runtime = GravityEntityAccess.cast(this).gravityengine$gravityComponent().runtime();
            fallResult = runtime.currentMoveResult();
            if (callbackMovement != null) {
                var parentResult = fallResult;
                var scene = runtime.collisionOperation();
                var movement = callbackMovement;
                callbackMovement = null;
                move(MoverType.SELF, movement);
                parentResultRestored = runtime.currentMoveResult() == parentResult;
                check(runtime.collisionOperation() == scene, "callback move borrows the same scene");
            }
            super.checkFallDamage(displacement, grounded, state, pos);
            if (callbackDestination != null) {
                setPos(callbackDestination);
                setDeltaMovement(callbackVelocity);
                callbackCompleted = true;
            }
        }
        @Override public void move(MoverType type, Vec3 movement) { moveCalls++; super.move(type, movement); }
        @Override public Vec3 handleRelativeFrictionAndCalculateMovement(Vec3 input, float friction) {
            relativeCalls++;
            if (changeLookInHelper) setYRot(90);
            return super.handleRelativeFrictionAndCalculateMovement(input, friction);
        }
    }
}
