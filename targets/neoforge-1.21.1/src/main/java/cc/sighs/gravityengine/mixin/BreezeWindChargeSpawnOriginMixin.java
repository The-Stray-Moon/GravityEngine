package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Breeze wind-charge constructor-origin bridge.
 *
 * <p>Vanilla 21.1.249 delegates
 * {@code BreezeWindCharge(Breeze, Level)} to
 * {@code AbstractWindCharge(EntityType, Level, Entity, x, snoutY, z)}.
 * The snout's anatomical {@code eye - 0.4} position is translated before
 * the delegated constructor performs its first authoritative position write.
 * Constructor-tail repositioning is forbidden.</p>
 */
@Mixin(BreezeWindCharge.class)
public abstract class BreezeWindChargeSpawnOriginMixin {
    private static final String OWNER_CONSTRUCTOR =
            "<init>(Lnet/minecraft/world/entity/monster/breeze/Breeze;"
                    + "Lnet/minecraft/world/level/Level;)V";

    @ModifyArgs(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/projectile/"
                                    + "windcharge/AbstractWindCharge;<init>("
                                    + "Lnet/minecraft/world/entity/EntityType;"
                                    + "Lnet/minecraft/world/level/Level;"
                                    + "Lnet/minecraft/world/entity/Entity;"
                                    + "DDD)V"
            ),
            require = 1
    )
    private static void gravityengine$breezeWindChargeSpawnOrigin(
            Args args,
            Breeze breeze,
            Level level
    ) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(breeze);
        if (!actor.customBody()) {
            return;
        }

        Vec3 origin =
                VanillaProjectileBridge.spawnOriginBelowEye(
                        actor,
                        0.4D
                );

        // Delegated ctor arguments:
        // 0 EntityType, 1 Level, 2 owner, 3 x, 4 y, 5 z.
        args.set(3, origin.x);
        args.set(4, origin.y);
        args.set(5, origin.z);
    }
}
