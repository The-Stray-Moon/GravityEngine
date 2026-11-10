package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.network.ServerPlayerMovementReceiver;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    /** 21.1.256 statistics retain native thresholds, exhaustion and stat selection. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "checkMovementStatistics")
    private void gravityengine$movementStatistics(double dx, double dy, double dz, Operation<Void> original) {
        var player = (ServerPlayer) (Object) this;
        if (!GravityInfluencePolicy.usesCustomCollision(player)) {
            original.call(dx, dy, dz);
            return;
        }
        var invocation = ServerPlayerMovementReceiver.current(player);
        var frame = invocation == null ? GravityFrameAccess.authoritativeFrame(player) : invocation.referenceFrame();
        var local = frame.worldToLocal(
                MinecraftMathAdapter.toVec3d(
                        new net.minecraft.world.phys.Vec3(dx, dy, dz)
                )
        );
        original.call(local.x(), local.y(), local.z());
    }

    /** Preserve native world displacement for support bookkeeping. Unknown custom
     * evidence defers this fall update; native/noPhysics remains entirely native. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "doCheckFallDamage", require = 1)
    private void gravityengine$packetFall(double dx, double dy, double dz, boolean ground, Operation<Void> original) {
        var invocation = ServerPlayerMovementReceiver.current((ServerPlayer) (Object) this);
        if (invocation == null) { original.call(dx, dy, dz, ground); return; }
        invocation.fall(() -> {
            var resolution = invocation.resolution();
            if (resolution instanceof ServerPlayerMovementReceiver.Unavailable) return;
            original.call(dx, dy, dz, resolution instanceof ServerPlayerMovementReceiver.Solved solved
                    ? solved.facts().fallDamageGrounded() : ground);
        });
    }

    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), require = 1)
    private void gravityengine$fallVertical(ServerPlayer actor, double vanilla,
            boolean ground, net.minecraft.world.level.block.state.BlockState state, BlockPos pos, Operation<Void> original) {
        var resolution = ServerPlayerMovementReceiver.activeFall((ServerPlayer) (Object) this);
        if (resolution instanceof ServerPlayerMovementReceiver.Unavailable) return;
        original.call(actor, resolution instanceof ServerPlayerMovementReceiver.Solved solved
                ? solved.facts().fallVertical() : vanilla, ground, state, pos);
    }

    /** Terminal material is frozen with the receipt, including dynamic storage coordinates.
     * Missing terminal material means air, never an unrelated world-foot block. */
    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getOnPosLegacy()Lnet/minecraft/core/BlockPos;"), require = 1)
    private BlockPos gravityengine$fallMaterial(ServerPlayer player, Operation<BlockPos> original) {
        var resolution = ServerPlayerMovementReceiver.activeFall(player);
        if (resolution instanceof ServerPlayerMovementReceiver.Unavailable) return BlockPos.ZERO;
        return resolution instanceof ServerPlayerMovementReceiver.Solved solved
                ? solved.material().map(ServerPlayerMovementReceiver.Material::position).orElse(BlockPos.ZERO)
                : original.call(player);
    }

    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1)
    private net.minecraft.world.level.block.state.BlockState gravityengine$fallMaterialState(
            net.minecraft.world.level.Level level, BlockPos pos,
            Operation<net.minecraft.world.level.block.state.BlockState> original) {
        var resolution = ServerPlayerMovementReceiver.activeFall((ServerPlayer) (Object) this);
        if (resolution instanceof ServerPlayerMovementReceiver.Unavailable) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return resolution instanceof ServerPlayerMovementReceiver.Solved solved
                ? solved.material().map(ServerPlayerMovementReceiver.Material::state)
                        .orElse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                : original.call(level, pos);
    }

    /** Material addresses may be plot-local; particles use the captured world contact. */
    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 1, allow = 1)
    private net.minecraft.world.phys.Vec3 gravityengine$fallParticlePosition(net.minecraft.world.phys.Vec3 position,
            double x, double y, double z, Operation<net.minecraft.world.phys.Vec3> original) {
        var resolution = ServerPlayerMovementReceiver.activeFall((ServerPlayer) (Object) this);
        if (resolution instanceof ServerPlayerMovementReceiver.Solved solved && solved.material().isPresent()) {
            return MinecraftMathAdapter.toMinecraft(solved.facts().terminalContact().orElseThrow().contactPoint()
                    .add(solved.facts().referenceFrame().up().multiply(0.5)));
        }
        return original.call(position, x, y, z);
    }

    @Inject(method = "onUpdateAbilities", at = @At("RETURN"))
    private void gravityengine$reconcile(CallbackInfo ci) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        GravityApplicationCoordinator.reconcileServerInfluence(p);
    }
}
