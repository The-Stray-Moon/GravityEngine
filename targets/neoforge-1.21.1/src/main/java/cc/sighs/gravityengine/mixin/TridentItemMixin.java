package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPropulsionBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Trident Riptide spatial operands.
 *
 * <p>Normal thrown Trident reuse the shared {@code AbstractArrow} owner-origin
 * and {@code Projectile.shootFromRotation} fixes and need no item-specific
 * patch.  Riptide's {@code player.push} direction is semantic view, and its
 * grounded {@code (0, 1.2, 0)} release is reference-up vertical.  Riptide
 * eligibility, wetness, sound, animation, damage, durability and statistics
 * remain Vanilla-owned.</p>
 */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    private static final String RELEASE_METHOD =
            "releaseUsing(Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/LivingEntity;I)V";

    @Inject(method = RELEASE_METHOD, at = @At("HEAD"))
    private void gravityengine$captureRiptidePlayer(
            ItemStack stack,
            Level level,
            net.minecraft.world.entity.LivingEntity entityLiving,
            int timeLeft,
            CallbackInfo ci,
            @Share("gravityengineRiptideActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        actorRef.set(entityLiving instanceof Player player
                ? VanillaActorBridge.capture(player)
                : null);
    }

    @WrapOperation(
            method = RELEASE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;"
                            + "push(DDD)V"
            ),
            require = 1
    )
    private void gravityengine$riptidePush(
            Player player,
            double x,
            double y,
            double z,
            Operation<Void> original,
            @Share("gravityengineRiptideActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (actor == null
                || !actor.transformedLook()) {
            original.call(player, x, y, z);
            return;
        }
        double strength = Math.sqrt(x * x + y * y + z * z);
        Vec3 direction = VanillaPropulsionBridge.propulsionDirection(
                actor, strength);
        original.call(
                player, direction.x, direction.y, direction.z);
    }

    @WrapOperation(
            method = RELEASE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;"
                            + "move(Lnet/minecraft/world/entity/MoverType;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"
            ),
            require = 1
    )
    private void gravityengine$riptideGroundRelease(
            Player player,
            MoverType moverType,
            Vec3 movement,
            Operation<Void> original,
            @Share("gravityengineRiptideActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (actor == null
                || !actor.nonDefaultReferenceFrame()) {
            original.call(player, moverType, movement);
            return;
        }
        Vec3 release = VanillaPropulsionBridge.groundedReleaseMovement(
                actor, movement.length());
        original.call(player, moverType, release);
    }
}
