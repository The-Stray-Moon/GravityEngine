package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 2.0.6 publication/lifecycle seams, on each side's Level owner. */
@Mixin(value = SubLevel.class, remap = false)
public abstract class SablePublicationMixin {
    @Inject(method = "updateLastPose", at = @At("RETURN"), require = 1)
    private void gravityengine$beginMotion(CallbackInfo ci) {
        SableRigidCollisionProvider.beginMotion((SubLevel)(Object)this);
    }
    @Inject(method = "updateBoundingBox", at = @At("RETURN"), require = 1)
    private void gravityengine$publishBounds(CallbackInfo ci) {
        SableRigidCollisionProvider.update((SubLevel)(Object)this);
    }
    @Inject(method = "markRemoved", at = @At("HEAD"), require = 1)
    private void gravityengine$remove(CallbackInfo ci) {
        SableRigidCollisionProvider.remove((SubLevel)(Object)this);
    }
}
