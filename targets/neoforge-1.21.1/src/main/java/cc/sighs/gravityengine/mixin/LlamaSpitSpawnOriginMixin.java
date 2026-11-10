package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 21.1.249 muzzle: anatomical eye - 0.1F, plus reference-relative yBodyRot
 * heading times (width + 1F)/2. Mob yaw is not installed OBB orientation.
 * Translate the one setPos argument tuple before any position observation.
 */
@Mixin(LlamaSpit.class)
public abstract class LlamaSpitSpawnOriginMixin {
    @ModifyArgs(
            method = "<init>(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/animal/horse/Llama;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/LlamaSpit;setPos(DDD)V"), require = 1
    )
    private void gravityengine$llamaSpitSpawnOrigin(
            Args args, Level level, Llama llama
    ) {
        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(llama);
        if (!actor.customBody()) {
            return;
        }
        double muzzleRadius =
                (double) (llama.getBbWidth() + 1.0F) * 0.5D;
        Vec3 origin = VanillaProjectileBridge.spawnOriginBelowEye(
                        actor, (double) 0.1F)
                .add(VanillaProjectileBridge.referenceYawForward(actor.referenceFrame(), llama.yBodyRot).scale(muzzleRadius));
        args.set(0, origin.x);
        args.set(1, origin.y);
        args.set(2, origin.z);
    }
}
