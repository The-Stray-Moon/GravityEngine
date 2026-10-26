package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ThrownTrident loyalty return vertical pre-convergence.
 *
 * <p>21.1.249 {@code ThrownTrident.tick} nudges world Y by
 * {@code ownerEyeDelta.y * 0.015 * loyalty}.  Under custom reference
 * semantics that vertical component belongs to the owner's reference up, not
 * world Y; the full-vector homing velocity that follows remains unchanged.
 */
@Mixin(ThrownTrident.class)
public abstract class ThrownTridentLoyaltyMixin {
    @Shadow @Final
    private static EntityDataAccessor<Byte> ID_LOYALTY;

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/ThrownTrident;"
                            + "setPosRaw(DDD)V"
            ),
            require = 1
    )
    private void gravityengine$referenceSpaceLoyaltyNudge(
            ThrownTrident trident,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        Entity owner = trident.getOwner();
        if (owner == null) {
            original.call(trident, x, y, z);
            return;
        }
        VanillaActorSnapshot ownerActor =
                VanillaActorBridge.capture(owner);
        if (!ownerActor.nonDefaultReferenceFrame()) {
            original.call(trident, x, y, z);
            return;
        }
        int loyalty = Byte.toUnsignedInt(
                trident.getEntityData().get(ID_LOYALTY));
        Vec3 toOwner = ownerActor.eye().subtract(trident.position());
        Vec3 up = ownerActor.referenceUp();
        double vertical = toOwner.dot(up);
        Vec3 adjusted = trident.position().add(
                up.scale(vertical * 0.015D * loyalty));
        original.call(
                trident, adjusted.x, adjusted.y, adjusted.z);
    }
}
