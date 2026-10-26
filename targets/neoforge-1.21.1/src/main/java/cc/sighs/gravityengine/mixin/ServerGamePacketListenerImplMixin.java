package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy;
import cc.sighs.gravityengine.gravity.integration.vanilla.RigidOccupancySnapshot;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.network.ServerPlayerMovementReceiver;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native movement operands and scoped client-owned endpoint acceptance.
 * Wrapped packets bypass speed/residual/endpoint/floating enforcement only.
 * Native input validation, physical move, callbacks and teleport bookkeeping remain.
 * Raw native packets retain their existing collision/reference operands.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Shadow private int awaitingTeleport;
    @Shadow private Vec3 awaitingPositionFromClient;

    // Native pending teleport owns the lifetime. Its position alone cannot say
    // whether a timeout resend is allowed to replace the receiver's look.
    @Unique private boolean gravityengine$pendingTeleportPreservesLook;
    @Unique private boolean gravityengine$pendingTeleportIsGeometryRelocation;

    /** All three .256 rejection producers (sleeping, speed, occupancy), only. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"),
            require = 3, allow = 3)
    private void gravityengine$positionOnlyReconciliation(ServerGamePacketListenerImpl listener,
            double x, double y, double z, float yaw, float pitch, Operation<Void> original) {
        // teleport takes absolute targets even for relative flags, subtracting
        // the current angles before absMoveTo. These operands yield wire 0/0.
        listener.teleport(x, y, z, this.player.getYRot(), this.player.getXRot(), RelativeMovement.ROTATION);
    }

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"), require = 1)
    private void gravityengine$capturePendingLookAuthority(double x, double y, double z,
            float yaw, float pitch, java.util.Set<RelativeMovement> relative, CallbackInfo ci) {
        var invocation = ServerPlayerMovementReceiver.current(this.player);
        if (invocation != null) invocation.reject();
        GravityEntityAccess.cast(this.player).gravityengine$gravityComponent().state().advanceBodyPublicationEpoch();
        if (cc.sighs.gravityengine.gravity.debug.GravityDebugLog.shouldLogMovement(this.player)) {
            cc.sighs.gravityengine.gravity.debug.GravityDebugLog.movement(this.player, "NETWORK", "native-teleport",
                    "target=%s,%s,%s wrapped=%s pending=%s", x, y, z,
                    gravityengine$clientEndpoint(), this.awaitingPositionFromClient != null);
        }
        gravityengine$pendingTeleportPreservesLook = relative.containsAll(RelativeMovement.ROTATION)
                && yaw == this.player.getYRot() && pitch == this.player.getXRot();
        gravityengine$pendingTeleportIsGeometryRelocation =
                cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.isGeometryRelocation(this.player);
    }

    /** Timeout must retain the pending producer's authority, including explicit teleports. */
    @WrapOperation(method = "updateAwaitingTeleport", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"),
            require = 1, allow = 1)
    private void gravityengine$resendPendingTeleport(ServerGamePacketListenerImpl listener,
            double x, double y, double z, float yaw, float pitch, Operation<Void> original) {
        if (gravityengine$pendingTeleportIsGeometryRelocation) {
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.geometryRelocation(this.player,
                    () -> listener.teleport(x, y, z, this.player.getYRot(), this.player.getXRot(), RelativeMovement.ROTATION));
        } else if (gravityengine$pendingTeleportPreservesLook) {
            listener.teleport(x, y, z, this.player.getYRot(), this.player.getXRot(), RelativeMovement.ROTATION);
        } else {
            original.call(listener, x, y, z, yaw, pitch);
        }
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("RETURN"), require = 1)
    private void gravityengine$finishPendingLookAuthority(
            net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (this.awaitingPositionFromClient == null) {
            gravityengine$pendingTeleportPreservesLook = false;
            gravityengine$pendingTeleportIsGeometryRelocation = false;
        }
    }

    /** .256 writes firstGood back after doTick; commit only AFTER that native boundary. */
    @WrapMethod(method = "tick", require = 1)
    private void gravityengine$bodyHandoffTick(Operation<Void> original) {
        try (var ignored = GravityApplicationBarrier.hold(this.player)) {
            original.call();
        }
        cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff
                .afterConnectionTick(this.player, this.awaitingPositionFromClient != null);
    }

    /** Keep the original Vanilla teleport ID and packet; change only its atomic transport envelope. */
    @WrapOperation(
            method = "teleport(DDDFFLjava/util/Set;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),
            require = 1, allow = 1
    )
    private void gravityengine$sendCommittedBody(
            ServerGamePacketListenerImpl listener,
            net.minecraft.network.protocol.Packet<?> packet,
            Operation<Void> original
    ) {
        if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket correction)) {
            original.call(listener, packet);
            return;
        }
        var commit = cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload.capture(this.player, correction);
        if (gravityengine$pendingTeleportIsGeometryRelocation) {
            commit = commit.withMode(cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload.CommitMode.RELOCATION);
        }
        original.call(listener, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(commit));
    }

    /**
     * Keep deferred application handoffs outside native movement acceptance.
     * Thread-local state is safe even on the initial network-thread call:
     * Vanilla schedules its own main-thread invocation and unwinds this scope.
     * No deferred work is flushed in finally; that could move the accepted
     * endpoint after Vanilla has already updated its bookkeeping.
     */
    @WrapMethod(method = "handleMovePlayer", require = 1)
    private void gravityengine$deferApplicationHandoffs(
            ServerboundMovePlayerPacket packet,
            Operation<Void> original
    ) {
        // The initial network-thread invocation only schedules Vanilla's main-thread call.
        if (!this.player.serverLevel().getServer().isSameThread()) {
            original.call(packet);
            return;
        }
        try (var invocation = ServerPlayerMovementReceiver.openHandler(this.player, packet);
             var ignored = GravityApplicationBarrier.hold(this.player)) {
            original.call(packet);
        }
    }

    /** The single root move in the pinned 21.1.256 handler (bytecode offset 717). */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"),
            require = 1, allow = 1)
    private void gravityengine$rootPacketMove(ServerPlayer actor, net.minecraft.world.entity.MoverType type,
            Vec3 movement, Operation<Void> original) {
        ServerPlayerMovementReceiver.current(actor).runRootMove(() -> original.call(actor, type, movement));
    }

    /**
     * Raw packets capture old geometry and one rigid snapshot for occupancy.
     * Wrapped packets skip this rejection-only evidence entirely.
     *
     * <p>This is Vanilla's first {@code ServerPlayer.getBoundingBox()} inside
     * {@code handleMovePlayer}. That seam is already after:
     * {@code PacketUtils.ensureRunningOnSameThread}, the invalid-value check,
     * horizontal/vertical clamping, the awaiting-teleport gate, the
     * passenger/sleeping branches and the moved-too-quickly rejection; and it
     * is before {@code jumpFromGround}, {@code player.move} and Vanilla's
     * old/new occupancy policy. The captured requested body uses Vanilla's
     * already-clamped {@code d0}/{@code d1}/{@code d2} operands (slots 3/5/7),
     * never the raw packet coordinates.</p>
     */
    @WrapOperation(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
                    ordinal = 0
            ),
            require = 1,
            allow = 1
    )
    private AABB gravityengine$captureOldBody(
            ServerPlayer actor,
            Operation<AABB> original,
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody,
            @Share("rigidOccupancySnapshot")
            LocalRef<RigidOccupancySnapshot> snapshot,
            @Local(index = 3) double targetX,
            @Local(index = 5) double targetY,
            @Local(index = 7) double targetZ
    ) {
        AABB bounds = original.call(actor);
        if (gravityengine$clientEndpoint()
                || actor == null
                || actor.noPhysics
                || (!GravityInfluencePolicy.usesExactBodyCollision(actor)
                    && !GravityInfluencePolicy.hasExternalCollisionProviders(actor))) {
            oldBody.set(null);
            return bounds;
        }

        CollisionBody previous =
                VanillaBodyOccupancy.capturePhysicalBody(actor);
        oldBody.set(previous);
        snapshot.set(
                RigidOccupancySnapshot.capture(
                        actor,
                        previous,
                        VanillaBodyOccupancy.atRequestedPosition(
                                previous,
                                actor.position(),
                                new Vec3(targetX, targetY, targetZ)
                        )
                )
        );
        if (!GravityInfluencePolicy.usesExactBodyCollision(actor)
                && !snapshot.get().indeterminate()) {
            var requested = VanillaBodyOccupancy.atRequestedPosition(previous, actor.position(),
                    new Vec3(targetX, targetY, targetZ));
            var corridor = previous.enclosingAabb().union(requested.enclosingAabb());
            if (snapshot.get().obstacles().stream().noneMatch(obstacle ->
                    !obstacle.providerNamespace().equals(cc.sighs.gravityengine.gravity.collision
                            .RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE)
                            && obstacle.operationSweptBounds().intersects(corridor))) {
                oldBody.set(null);
            }
        }
        return bounds;
    }

    /** .256's speed-only singleplayer exemption; does not affect sleeping distance. */
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
            method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"),
            require = 1, allow = 1)
    private boolean gravityengine$clientOwnedSpeed(boolean original) {
        return gravityengine$clientEndpoint() || original;
    }

    @Unique private boolean gravityengine$clientEndpoint() {
        return ServerPlayerMovementReceiver.endpointPolicy(this.player)
                == ServerPlayerMovementReceiver.EndpointPolicy.CLIENT_ENDPOINT;
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"
            ),
            require = 1
    )
    private boolean gravityengine$oldBodyClear(
            ServerLevel level,
            Entity actor,
            AABB box,
            Operation<Boolean> original,
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody,
            @Share("rigidOccupancySnapshot")
            LocalRef<RigidOccupancySnapshot> snapshot
    ) {
        if (gravityengine$clientEndpoint()) return false;
        CollisionBody previous = oldBody.get();
        if (previous == null) {
            // The application barrier keeps this invocation on its captured
            // Vanilla geometry path; a pending capability handoff runs later.
            return original.call(level, actor, box);
        }
        return VanillaBodyOccupancy.oldBodyClear(
                level,
                (ServerPlayer) actor,
                box,
                previous,
                snapshot.get()
        );
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isPlayerCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/phys/AABB;DDD)Z"
            ),
            require = 1
    )
    private boolean gravityengine$newBodyOccupancy(
            ServerGamePacketListenerImpl listener,
            LevelReader level,
            AABB box,
            double x, double y, double z,
            Operation<Boolean> original,
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody,
            @Share("rigidOccupancySnapshot")
            LocalRef<RigidOccupancySnapshot> snapshot
    ) {
        if (gravityengine$clientEndpoint()) return false;
        CollisionBody previous = oldBody.get();
        if (previous == null) {
            return original.call(listener, level, box, x, y, z);
        }
        CollisionBody requested = VanillaBodyOccupancy.atRequestedPosition(
                VanillaBodyOccupancy.capturePhysicalBody(this.player),
                this.player.position(),
                new Vec3(x, y, z)
        );
        return VanillaBodyOccupancy.hasNewCollision(
                level,
                this.player,
                previous,
                requested,
                snapshot.get()
        );
    }

    /**
     * 21.1.256 {@code flag4 = d7 > 0.0} is Vanilla's "the player moved upward"
     * operand. It gates jump inference ({@code onGround && !packet.onGround &&
     * upward} -&gt; {@code jumpFromGround}) and the accepted-branch fall-distance
     * reset. Floating has its own residual and sensor operands. {@code d7} is the packet displacement's
     * world-Y component, which is not the movement's vertical quantity under
     * arbitrary gravity; the comparable operand is the displacement's component
     * along the captured environmental up. The jump invocation below additionally
     * requires physical support departure; this shared operand alone is not
     * evidence of a jump. The reset call also checks the completed fall measurement;
     * unavailable custom evidence cannot clear accumulated fall distance.
     */
    @ModifyVariable(method = "handleMovePlayer", at = @At("STORE"), index = 29, require = 1)
    private boolean gravityengine$environmentalUpwardMovement(boolean vanilla,
            @Local(index = 17) double dx, @Local(index = 19) double dy, @Local(index = 21) double dz) {
        GravityFrame frame = gravityengine$environmentalFrame();
        return frame == null
                ? vanilla
                : new Vec3(dx, dy, dz).dot(
                        MinecraftMathAdapter.toMinecraft(
                                        frame.up()))
                        > 0.0D;
    }

    /** Vanilla correction replies carry onGround=false even on a supporting
     * face. Positive reference-up displacement can be uphill tangent motion.
     * Keep native gates/callbacks, but require current physical departure before
     * allowing the inferred impulse. Locals verified against 21.1.256 bytecode. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;jumpFromGround()V"), require = 1, allow = 1)
    private void gravityengine$inferSupportedJump(ServerPlayer actor, Operation<Void> original,
            @Local(index = 17) double dx, @Local(index = 19) double dy, @Local(index = 21) double dz) {
        if (!GravityInfluencePolicy.usesCustomCollision(actor)
                || cc.sighs.gravityengine.gravity.integration.collision.GravityJumpSupportQuery
                        .departsStationarySupport(actor, new cc.sighs.gravityengine.api.math.Vec3d(dx, dy, dz))) {
            original.call(actor);
        }
    }

    /**
     * The second {@code d10} store is the residual error gate after Vanilla's
     * one physical move ({@code d6*d6 + d7*d7 + d8*d8}, where Vanilla always
     * clears the world-Y term). Under arbitrary gravity the comparable
     * displacement quantity is the residual inside the installed collision tangent plane,
     * not world XZ. Wrapped packets use zero at this gate; raw packets retain
     * the native threshold and rejection policy (including Sable's bypass).
     */
    @ModifyVariable(method = "handleMovePlayer", at = @At(value = "STORE", ordinal = 1), index = 25, require = 1)
    private double gravityengine$collisionTangentResidual(double vanilla,
            @Local(index = 3) double x, @Local(index = 5) double y, @Local(index = 7) double z) {
        if (gravityengine$clientEndpoint()) return 0.0D;
        GravityFrame frame = gravityengine$collisionFrame();
        if (frame == null) return vanilla;
        cc.sighs.gravityengine.api.math.Vec3d tangent =
                frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(
                                        new Vec3(x, y, z).subtract(
                                                this.player.position())));
        return tangent.x() * tangent.x() + tangent.z() * tangent.z();
    }

    /** Accepted gameplay continuity belongs to this packet, never another move in its tick. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"),
            require = 1, allow = 1)
    private void gravityengine$authoritativeGround(ServerPlayer actor, boolean vanillaGrounded,
            Vec3 movement, Operation<Void> original) {
        var invocation = ServerPlayerMovementReceiver.current(actor);
        invocation.accept();
        original.call(actor, invocation.gameplayGrounded(vanillaGrounded), movement);
    }

    /** Upward packet intent gates the native call, but only actual environmental
     * locomotion may clear custom fall accumulation; recovery is excluded. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;resetFallDistance()V"), require = 1, allow = 1)
    private void gravityengine$environmentalFallReset(ServerPlayer actor, Operation<Void> original) {
        var resolution = ServerPlayerMovementReceiver.current(actor).resolution();
        if (resolution instanceof ServerPlayerMovementReceiver.NativeOwned
                || resolution instanceof ServerPlayerMovementReceiver.Solved solved && solved.facts().fallVertical() > 0) {
            original.call(actor);
        }
    }

    /**
     * Vanilla's anti-floating sensor expands the bounding box downward in world
     * -Y. Only that operand is reference-relative; the sensor's dimensions,
     * material predicate and policy gates stay Vanilla's for raw packets.
     * Wrapped packets report false so the accepted floating flag is cleared;
     * Vanilla's next connection tick then clears its above-ground counter.
     */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;noBlocksAround(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1)
    private boolean gravityengine$referenceFloatingSensor(ServerGamePacketListenerImpl listener,
            Entity entity, Operation<Boolean> original) {
        if (gravityengine$clientEndpoint()) return false;
        GravityFrame frame = gravityengine$environmentalFrame();
        if (frame == null) return original.call(listener, entity);
        return entity.level().getBlockStates(entity.getBoundingBox().inflate(0.0625D)
                        .expandTowards(
                                MinecraftMathAdapter.toMinecraft(
                                                frame.down()
                                                        .multiply(0.55D))))
                .allMatch(BlockBehaviour.BlockStateBase::isAir);
    }

    @Inject(method = "handlePlayerAbilities", at = @At("RETURN"))
    private void gravityengine$abilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        GravityApplicationCoordinator.reconcileServerInfluence(this.player);
    }

    /** Captured environmental/control evidence for jump intent and floating. */
    @Unique private GravityFrame gravityengine$environmentalFrame() {
        return GravityInfluencePolicy.usesCustomCollision(this.player)
                ? ServerPlayerMovementReceiver.current(this.player).referenceFrame() : null;
    }

    /** Collision residuals use the installed axis, independently of environmental up. */
    @Unique private GravityFrame gravityengine$collisionFrame() {
        return GravityInfluencePolicy.usesCustomCollision(this.player)
                ? GravityFrameAccess.installedLocomotionFrame(this.player) : null;
    }
}
