package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boat placement candidate corridor.
 *
 * <p>The block ray itself continues through the fixed
 * {@code Item.getPlayerPOVHitResult} seam.  This mixin replaces only the
 * separate corridor direction and occupant-eye origin used by
 * {@code BoatItem.use}, which bypass that shared ray.</p>
 */
@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    @Inject(method = "use", at = @At("HEAD"))
    private void gravityengine$captureBoatActor(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack>> ci,
            @Share("gravityengineBoatActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        actorRef.set(VanillaActorBridge.capture(player));
    }

    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;"
                            + "getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$boatCorridorDirection(
            Player player,
            float partialTick,
            Operation<Vec3> original,
            @Share("gravityengineBoatActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        return !actor.transformedLook()
                ? original.call(player, partialTick)
                : actor.viewForward();
    }

    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;"
                            + "getEyePosition()Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private Vec3 gravityengine$boatOccupantEye(
            Player player,
            Operation<Vec3> original,
            @Share("gravityengineBoatActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        return !actor.customBody()
                ? original.call(player)
                : actor.eye();
    }
}
