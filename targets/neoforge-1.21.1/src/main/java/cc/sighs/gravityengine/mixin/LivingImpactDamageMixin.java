package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.ImpactDamageIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(LivingEntity.class)
public abstract class LivingImpactDamageMixin {
    /** Keep NeoForge's LivingFallEvent and native immunity, sound and hurt ordering.
     * The integer only selects native sound/branch; actual damage stays fractional. */
    @WrapOperation(method = "causeFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;calculateFallDamage(FF)I"), require = 1)
    private int gravityengine$impactAmount(LivingEntity entity, float distance, float multiplier,
            Operation<Integer> original, @Share("impactDamage") LocalFloatRef damage) {
        if (!ImpactDamageIntegration.applying(entity)) return original.call(entity, distance, multiplier);
        float amount = entity.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE) ? 0 : (float)Math.max(0,
                (distance - entity.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)) * multiplier
                        * entity.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        damage.set(amount);
        return net.minecraft.util.Mth.ceil(amount);
    }

    @WrapOperation(method = "causeFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), require = 1)
    private boolean gravityengine$fractionalImpact(LivingEntity entity, DamageSource source, float amount,
            Operation<Boolean> original, @Share("impactDamage") LocalFloatRef damage) {
        return original.call(entity, source, ImpactDamageIntegration.applying(entity) ? damage.get() : amount);
    }

    /** Entity.move already settled this impact before clipping velocity. */
    @WrapOperation(method = "travel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/LivingEntity;horizontalCollision:Z"),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;")), require = 1)
    private boolean gravityengine$singleImpact(LivingEntity entity,
            Operation<Boolean> original) {
        return false;
    }
}
