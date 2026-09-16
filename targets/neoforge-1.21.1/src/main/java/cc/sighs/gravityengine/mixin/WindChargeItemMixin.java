package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wind-charge spawn origin.
 *
 * <p>21.1.249 {@code WindChargeItem.use} mixes {@code player.position()}
 * X/Z with {@code getEyePosition().y}.  For a custom physical body the full
 * gameplay physical eye is the origin; launch direction continues through
 * {@code Projectile.shootFromRotation} and its shared semantic fix.</p>
 */
@Mixin(WindChargeItem.class)
public abstract class WindChargeItemMixin {
    @Inject(method = "use", at = @At("HEAD"))
    private void gravityengine$captureWindChargeActor(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack>> ci,
            @Share("gravityengineWindChargeActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        actorRef.set(VanillaActorBridge.capture(player));
    }

    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;"
                            + "position()Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 2
    )
    private Vec3 gravityengine$windChargeFeetCoordinate(
            Player player,
            Operation<Vec3> original,
            @Share("gravityengineWindChargeActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (!actor.customBody()) {
            return original.call(player);
        }
        return actor.eye();
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
    private Vec3 gravityengine$windChargeEyeCoordinate(
            Player player,
            Operation<Vec3> original,
            @Share("gravityengineWindChargeActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (!actor.customBody()) {
            return original.call(player);
        }
        return actor.eye();
    }
}
