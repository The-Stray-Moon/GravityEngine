package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applied after Sable's priority-1000 Entity mixin. Its point-only veto must
 * not override GE's already clipped physical fluid metadata. */
@Mixin(value=Entity.class,priority=900)
public abstract class SableWaterOcclusionMixin {
    @Dynamic("Sable 2.0.6 water_occlusion.EntityMixin unique helper")
    @Inject(method="sable$isOccluded",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void gravityengine$capturedOcclusion(CallbackInfoReturnable<Boolean> ci) {
        var entity=(Entity)(Object)this;
        if(entity instanceof LivingEntity && !entity.isPassenger() && !entity.noPhysics
                && GravityInfluencePolicy.usesCustomLocomotion(entity)) ci.setReturnValue(false);
    }
}
