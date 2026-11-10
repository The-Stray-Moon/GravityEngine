package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class CreateContraptionGeometryMixin {
    @Inject(method={"tick","setContraption"},at={@At("HEAD"),@At("RETURN")},require=1)
    private void gravityengine$motionBoundary(CallbackInfo ci) {
        CreateRigidCollisionProvider.motionChanged((AbstractContraptionEntity)(Object)this);
    }
    @Inject(method = "setBlock", at = @At("RETURN"), require = 1)
    private void gravityengine$edit(BlockPos position, StructureTemplate.StructureBlockInfo block, CallbackInfo ci) {
        CreateRigidCollisionProvider.changed((AbstractContraptionEntity)(Object)this, position);
    }
}
