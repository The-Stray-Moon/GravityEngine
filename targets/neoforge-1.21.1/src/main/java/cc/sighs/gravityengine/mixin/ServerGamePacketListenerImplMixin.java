package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.MovementGroundContinuity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;

/**
 * Arbitrary-gravity operand translation inside Vanilla's ordinary player
 * position-packet handling.
 *
 * <p>Ownership: Vanilla owns the whole {@code handleMovePlayer} lifecycle,
 * including invalid-value checks, packet-count/speed policy, the single
 * {@code player.move(MoverType.PLAYER, ...)}, moved-wrongly and new-collision
 * policy, accept/reject, correction/teleport bookkeeping, {@code lastGood}/
 * {@code firstGood}, chunk tracking, movement statistics and fall handling.
 * This mixin never accepts, rejects, rolls back, re-commits or re-solves a
 * packet position.</p>
 *
 * <p>Reference-relative quantities use the installed environmental frame;
 * physical occupancy uses the installed exact body. Each seam retains its
 * original operand when that custom capability is inactive. Vanilla policy
 * stays unchanged, including for independent bodies at default gravity.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Shadow private int awaitingTeleport;
    @Shadow private Vec3 awaitingPositionFromClient;

    /** .249 writes firstGood back after doTick; commit only AFTER that native boundary. */
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
        try (var ignored = GravityApplicationBarrier.hold(this.player)) {
            original.call(packet);
        }
    }

    /** Preserve the old geometry once, at Vanilla's old-bounds capture. */
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
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody
    ) {
        AABB bounds = original.call(actor);
        oldBody.set(GravityInfluencePolicy.usesExactBodyCollision(actor)
                ? VanillaBodyOccupancy.capturePhysicalBody(actor)
                : null);
        return bounds;
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
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody
    ) {
        CollisionBody previous = oldBody.get();
        if (previous == null && !GravityInfluencePolicy.usesExactBodyCollision(actor)) {
            return original.call(level, actor, box);
        }
        return VanillaBodyOccupancy.oldBodyClear(
                level, actor, box,
                previous != null ? previous : VanillaBodyOccupancy.fromVanillaBounds(box)
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
            @Share("oldExactBody") LocalRef<CollisionBody> oldBody
    ) {
        CollisionBody previous = oldBody.get();
        if (previous == null && !GravityInfluencePolicy.usesExactBodyCollision(this.player)) {
            return original.call(listener, level, box, x, y, z);
        }
        return VanillaBodyOccupancy.hasNewCollision(
                level, this.player,
                previous != null ? previous : VanillaBodyOccupancy.fromVanillaBounds(box),
                x, y, z
        );
    }

    /** The six-argument overload is the single Vanilla teleport packet producer:
     * commands, corrections and the five-argument overload all reach this seam. */
    @WrapMethod(method = "teleport(DDDFFLjava/util/Set;)V", require = 1)
    private void gravityengine$traceViewTeleport(double x, double y, double z, float yaw, float pitch,
            java.util.Set<RelativeMovement> relative, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED) { original.call(x, y, z, yaw, pitch, relative); return; }
        try (var trace = PlayerViewDebugLog.begin(player, "server-teleport",
                "targetP=%s targetYaw=%s targetPitch=%s relative=%s wireYaw=%s wirePitch=%s",
                new Vec3(x, y, z), yaw, pitch, relative,
                yaw - (relative.contains(RelativeMovement.Y_ROT) ? player.getYRot() : 0),
                pitch - (relative.contains(RelativeMovement.X_ROT) ? player.getXRot() : 0))) {
            original.call(x, y, z, yaw, pitch, relative);
            PlayerViewDebugLog.event(player, "server-teleport-sent", "teleportId=%s relative=%s",
                    this.awaitingTeleport, relative);
        }
    }

    /**
     * 21.1.249 {@code flag4 = d7 > 0.0} is Vanilla's "the player moved upward"
     * operand. It gates jump inference ({@code onGround && !packet.onGround &&
     * upward} -&gt; {@code jumpFromGround}), the accepted-branch fall-distance
     * reset and the floating check. {@code d7} is the packet displacement's
     * world-Y component, which is not the movement's vertical quantity under
     * arbitrary gravity; the comparable operand is the displacement's component
     * along the installed reference up. Vanilla keeps the inferred jump, its
     * impulse, the reset policy and the floating heuristic.
     */
    @ModifyVariable(method = "handleMovePlayer", at = @At("STORE"), index = 29, require = 1)
    private boolean gravityengine$referenceUpwardMovement(boolean vanilla,
            @Local(index = 17) double dx, @Local(index = 19) double dy, @Local(index = 21) double dz) {
        GravityFrame frame = gravityengine$referenceFrame();
        return frame == null ? vanilla : new Vec3(dx, dy, dz).dot(frame.up()) > 0.0D;
    }

    /**
     * The second {@code d10} store is the residual error gate after Vanilla's
     * one physical move ({@code d6*d6 + d7*d7 + d8*d8}, where Vanilla always
     * clears the world-Y term). Under arbitrary gravity the comparable
     * displacement quantity is the residual inside the reference tangent plane,
     * not world XZ. Vanilla's threshold, gates, moved-wrongly flag and
     * correction policy are unchanged.
     */
    @ModifyVariable(method = "handleMovePlayer", at = @At(value = "STORE", ordinal = 1), index = 25, require = 1)
    private double gravityengine$referenceTangentResidual(double vanilla,
            @Local(index = 3) double x, @Local(index = 5) double y, @Local(index = 7) double z) {
        GravityFrame frame = gravityengine$referenceFrame();
        if (frame == null) return vanilla;
        Vec3 tangent = frame.worldToLocal(new Vec3(x, y, z).subtract(this.player.position()));
        return tangent.x * tangent.x + tangent.z * tangent.z;
    }

    /**
     * Vanilla writes packet {@code isOnGround} into gameplay ground state after
     * acceptance. A determinate custom move owns its own gameplay grounding for
     * the same tick; use that publication when present and otherwise retain the
     * packet value. The flag is a contact fact supplied to Vanilla's own
     * bookkeeping, never a position-packet decision.
     */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"),
            require = 1)
    private void gravityengine$authoritativeGround(ServerPlayer acceptedPlayer, boolean vanillaGrounded,
            Vec3 movement, Operation<Void> original) {
        original.call(acceptedPlayer, gravityengine$resolvedGround(vanillaGrounded), movement);
    }

    /** The same grounded fact feeds Vanilla's fall callback on both branches. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;doCheckFallDamage(DDDZ)V"), require = 2)
    private void gravityengine$acceptedFallGround(ServerPlayer correctedPlayer, double dx, double dy, double dz,
            boolean vanillaGrounded, Operation<Void> original) {
        original.call(correctedPlayer, dx, dy, dz, gravityengine$resolvedGround(vanillaGrounded));
    }

    /**
     * Vanilla's anti-floating sensor expands the bounding box downward in world
     * -Y. Only that operand is reference-relative; the sensor's dimensions,
     * material predicate and policy gates stay Vanilla's.
     */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;noBlocksAround(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1)
    private boolean gravityengine$referenceFloatingSensor(ServerGamePacketListenerImpl listener,
            Entity entity, Operation<Boolean> original) {
        GravityFrame frame = gravityengine$referenceFrame();
        if (frame == null) return original.call(listener, entity);
        return entity.level().getBlockStates(entity.getBoundingBox().inflate(0.0625D)
                        .expandTowards(frame.down().scale(0.55D)))
                .allMatch(BlockBehaviour.BlockStateBase::isAir);
    }

    @Inject(method = "handlePlayerAbilities", at = @At("RETURN"))
    private void gravityengine$abilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        GravityApplicationCoordinator.reconcileServerInfluence(this.player);
    }

    /** Installed reference frame of a custom-gravity player, or null for Vanilla-owned movement. */
    @Unique private GravityFrame gravityengine$referenceFrame() {
        return GravityInfluencePolicy.usesCustomCollision(this.player)
                ? GravityFrameAccess.authoritativeFrame(this.player)
                : null;
    }

    /** Same-tick custom grounding publication; the packet's own value is the fallback. */
    @Unique private boolean gravityengine$resolvedGround(boolean vanillaGrounded) {
        return GravityEntityAccess.cast(this.player).gravityengine$gravityComponent().runtime()
                .lastCommittedMovementGroundContinuity(this.player.level().getGameTime())
                .map(MovementGroundContinuity::gameplayGrounded)
                .orElse(vanillaGrounded);
    }
}
