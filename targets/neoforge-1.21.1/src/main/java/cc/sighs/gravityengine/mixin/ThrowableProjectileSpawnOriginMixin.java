package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Shared throwable owner-constructor spawn-origin bridge.
 *
 * <p>Vanilla 21.1.249 delegates the owner constructor to
 * {@code ThrowableProjectile(EntityType, x, eyeY - 0.1, z, Level)}.
 * The anatomical below-eye offset is translated before that delegated
 * constructor performs the projectile's first {@code setPos}. This preserves
 * a single authoritative initial-position commit; no constructor-tail
 * reposition is permitted.</p>
 */
@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileSpawnOriginMixin {
    private static final String OWNER_CONSTRUCTOR =
            "<init>(Lnet/minecraft/world/entity/EntityType;"
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/level/Level;)V";

    @ModifyArgs(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/projectile/"
                                    + "ThrowableProjectile;<init>("
                                    + "Lnet/minecraft/world/entity/EntityType;"
                                    + "DDDLnet/minecraft/world/level/Level;)V"
            ),
            require = 1
    )
    private static void gravityengine$throwableSpawnOrigin(
            Args args,
            EntityType<?> type,
            LivingEntity owner,
            Level level
    ) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(owner);
        if (!actor.customBody()) {
            return;
        }

        Vec3 origin =
                VanillaProjectileBridge.spawnOriginBelowEye(
                        actor,
                        (double) 0.1F
                );

        // Delegated ctor arguments:
        // 0 EntityType, 1 x, 2 y, 3 z, 4 Level.
        args.set(1, origin.x);
        args.set(2, origin.y);
        args.set(3, origin.z);
    }
}
