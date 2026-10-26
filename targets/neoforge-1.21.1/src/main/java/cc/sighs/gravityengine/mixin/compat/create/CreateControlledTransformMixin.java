package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ControlledContraptionEntity.class,remap=false)
public abstract class CreateControlledTransformMixin {
    @Inject(method={"setAngle","setRotationAxis","readAdditional"},at=@At("RETURN"),require=1)
    private void gravityengine$rotation(CallbackInfo ci) {
        CreateRigidCollisionProvider.motionChanged((ControlledContraptionEntity)(Object)this);
    }
}
