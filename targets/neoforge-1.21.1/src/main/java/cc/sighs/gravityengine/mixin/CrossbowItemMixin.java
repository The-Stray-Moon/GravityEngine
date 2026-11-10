package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Crossbow direct and target firing.
 *
 * <p>21.1.249 {@code CrossbowItem.shootProjectile} has separate direct and
 * target branches.  This mixin captures one shooter actor snapshot per
 * invocation and replaces only the {@code Projectile.shoot} operand vector.
 * Direct shots use semantic view forward/up for multishot rotation; target
 * shots decompose horizontal distance in the shooter reference tangent plane
 * and apply Vanilla's 0.2 rise along reference up.  Multishot angles,
 * inaccuracy, RNG, sound and policy remain Vanilla-owned.  Firework origin is
 * corrected at {@code createProjectile} to {@code eye + bodyDown * 0.15}.</p>
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    private static final String SHOOT_METHOD =
            "shootProjectile(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/projectile/Projectile;"
                    + "IFFFLnet/minecraft/world/entity/LivingEntity;)V";

    @Inject(
            method = SHOOT_METHOD,
            at = @At("HEAD")
    )
    private void gravityengine$captureCrossbowShooter(
            LivingEntity shooter,
            Projectile projectile,
            int index,
            float velocity,
            float inaccuracy,
            float angle,
            @Nullable LivingEntity target,
            CallbackInfo ci,
            @Share("gravityengineCrossbowContext")
            LocalRef<CrossbowContext> contextRef
    ) {
        VanillaActorSnapshot shooterActor =
                VanillaActorBridge.capture(shooter);

        contextRef.set(
                new CrossbowContext(
                        shooterActor,
                        target,
                        angle
                )
        );
    }

    @WrapOperation(
            method = SHOOT_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/Projectile;"
                            + "shoot(DDDFF)V"
            ),
            require = 1
    )
    private void gravityengine$crossbowShotVector(
            Projectile projectile,
            double x,
            double y,
            double z,
            float velocity,
            float inaccuracy,
            Operation<Void> original,
            @Share("gravityengineCrossbowContext")
            LocalRef<CrossbowContext> contextRef
    ) {
        CrossbowContext context = contextRef.get();
        VanillaActorSnapshot shooter = context.shooterActor();
        if (context.target() != null) {
            LivingEntity target =
                    context.target();

            if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(target)
                    && !shooter.nonDefaultReferenceFrame()) {
                original.call(
                        projectile,
                        x,
                        y,
                        z,
                        velocity,
                        inaccuracy
                );
                return;
            }

            Vec3 targetPoint =
                    VanillaProjectileBridge.bodyHeightPoint(
                            target,
                            1.0D / 3.0D
                    );

            Vec3 direction =
                    VanillaProjectileBridge.crossbowTargetDirection(
                                    shooter,
                                    projectile.position(),
                                    targetPoint,
                                    VanillaProjectileBridge.CROSSBOW_TARGET_RISE,
                                    context.angleDegrees()
                            );

            original.call(
                    projectile,
                    direction.x,
                    direction.y,
                    direction.z,
                    velocity,
                    inaccuracy
            );
            return;
        }
        if (!shooter.transformedLook()) {
            original.call(projectile, x, y, z, velocity, inaccuracy);
            return;
        }
        Vec3 direction;
        direction = VanillaProjectileBridge.crossbowDirectDirection(
                shooter, context.angleDegrees());
        original.call(
                projectile,
                direction.x,
                direction.y,
                direction.z,
                velocity,
                inaccuracy
        );
    }

    private record CrossbowContext(
            VanillaActorSnapshot shooterActor,
            @Nullable LivingEntity target,
            float angleDegrees
    ) {}

    /**
     * 21.1.249 {@code createProjectile} constructs the firework at
     * {@code (shooter.x, eyeY - 0.15, shooter.z)}.  The world-Y subtraction
     * happens in this Vanilla method, after any patched scalar accessor, so
     * scalar wrapping would double the offset.  Replace the complete
     * firework constructor with the full world-space origin
     * {@code eye + bodyDown * 0.15}; firework item, owner, angle flag,
     * multishot lifecycle and sound remain Vanilla-owned.
     */
    @WrapMethod(
            method = "createProjectile"
                    + "(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemStack;Z)"
                    + "Lnet/minecraft/world/entity/projectile/Projectile;"
    )
    private Projectile gravityengine$crossbowFireworkOrigin(
            Level level,
            LivingEntity shooter,
            ItemStack weapon,
            ItemStack ammo,
            boolean isCrit,
            Operation<Projectile> original
    ) {
        if (!ammo.is(Items.FIREWORK_ROCKET)
                || !cc.sighs.gravityengine.gravity.policy
                        .GravityInfluencePolicy
                        .usesCustomBody(shooter)) {
            return original.call(
                    level, shooter, weapon, ammo, isCrit);
        }
        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(shooter);
        Vec3 origin = VanillaProjectileBridge.spawnOriginBelowEye(
                actor, 0.15D);
        return new FireworkRocketEntity(
                level,
                ammo,
                shooter,
                origin.x,
                origin.y,
                origin.z,
                true
        );
    }
}
