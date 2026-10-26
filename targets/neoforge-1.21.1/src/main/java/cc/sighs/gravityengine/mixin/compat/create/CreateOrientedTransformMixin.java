package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=OrientedContraptionEntity.class,remap=false)
public abstract class CreateOrientedTransformMixin {
    @Inject(method={"setInitialOrientation","readAdditional"},at=@At("RETURN"),require=1)
    private void gravityengine$orientation(CallbackInfo ci) {
        CreateRigidCollisionProvider.motionChanged((OrientedContraptionEntity)(Object)this);
    }
}
