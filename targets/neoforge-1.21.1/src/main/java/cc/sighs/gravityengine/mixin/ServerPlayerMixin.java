package cc.sighs.gravityengine.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    /** 21.1.249 statistics retain native thresholds, exhaustion and stat selection. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "checkMovementStatistics")
    private void gravityengine$movementStatistics(double dx, double dy, double dz, Operation<Void> original) {
        var player = (ServerPlayer) (Object) this;
        if (!GravityInfluencePolicy.usesCustomCollision(player)) {
            original.call(dx, dy, dz);
            return;
        }
        var local = GravityFrameAccess.reliableFrame(player).worldToLocal(
                new net.minecraft.world.phys.Vec3(dx, dy, dz));
        original.call(local.x, local.y, local.z);
    }

    /** ServerPlayer deliberately skips Entity.move's fall damage and runs it once
     * after packet acceptance. Preserve that ordering, policy and world movement
     * passed to support tracking; translate only Player.checkFallDamage's vertical. */
    @ModifyArg(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), index = 0, require = 1)
    private double gravityengine$fallVertical(double vanilla) {
        var player = (ServerPlayer) (Object) this;
        if (!GravityInfluencePolicy.usesCustomCollision(player)) return vanilla;
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
        var result = runtime.currentMoveResult();
        return result != null && result.isAuthoritative()
                ? result.fallDistanceVertical() : vanilla;
    }

    /** Landing material follows the terminal support witness. The no-support
     * Vanilla sensor is offset along gravity down; it does not invent support. */
    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getOnPosLegacy()Lnet/minecraft/core/BlockPos;"), require = 1)
    private BlockPos gravityengine$fallMaterial(ServerPlayer player, Operation<BlockPos> original) {
        if (!GravityInfluencePolicy.usesCustomCollision(player)) return original.call(player);
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
        if (player.noPhysics) return original.call(player);
        var frame = GravityFrameAccess.reliableFrame(player);
        var result = runtime.currentMoveResult();
        return (result == null ? java.util.Optional.<BlockPos>empty() : result.authoritativeSupport())
                .orElseGet(() -> VanillaBodySensors.gravityRelativeOnPos(frame,
                        cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.gravityFeet(player, frame)));
    }

    @Inject(method = "onUpdateAbilities", at = @At("RETURN"))
    private void gravityengine$reconcile(CallbackInfo ci) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        GravityApplicationCoordinator.reconcileServerInfluence(p);
    }
}
