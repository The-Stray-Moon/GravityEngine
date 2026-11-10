package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 21.1.249 owner constructor delegates coordinates before setOwner. A static
 * ModifyArgs handler needs no uninitialized this: the coordinate constructor's
 * setPos and EnchantmentHelper.onProjectileSpawned see the corrected origin.
 * Item, enchantment, owner and callback ordering remain Vanilla-owned.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowSpawnOriginMixin {
    private static final String OWNER_CONSTRUCTOR =
            "<init>(Lnet/minecraft/world/entity/EntityType;"
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemStack;)V";

    @ModifyArgs(method = OWNER_CONSTRUCTOR, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;<init>(Lnet/minecraft/world/entity/EntityType;DDDLnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"), require = 1)
    private static void gravityengine$arrowSpawnOrigin(
            Args args, EntityType<?> type, LivingEntity owner, Level level,
            ItemStack pickupItemStack, ItemStack firedFromWeapon) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(owner);
        if (!actor.customBody()) return;
        var origin = VanillaProjectileBridge.spawnOriginBelowEye(actor, (double) 0.1F);
        args.set(1, origin.x);
        args.set(2, origin.y);
        args.set(3, origin.z);
    }
}
