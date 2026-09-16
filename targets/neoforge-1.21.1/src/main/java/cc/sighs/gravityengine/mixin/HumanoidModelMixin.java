package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientCharacterPresentation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge 21.1.249 model-local swim seams. Vanilla retains animation policy;
 * Qbody's existing render scope replaces PlayerRenderer.setupRotations entirely.
 * No entity swimming flag or global swim/Elytra rotation is installed here.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final public net.minecraft.client.model.geom.ModelPart head;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final public net.minecraft.client.model.geom.ModelPart hat;

    @org.spongepowered.asm.mixin.Unique private boolean gravityengine$ownedHead;

    /** Vanilla does not reset head roll; release our previous model mutation before its animation. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD"), require = 1)
    private void gravityengine$releaseHead(LivingEntity entity, float swing, float amount, float age,
            float yaw, float pitch, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (gravityengine$ownedHead) { head.zRot = 0; hat.zRot = 0; gravityengine$ownedHead = false; }
    }

    /** ModelPart uses local ZYX; final controller head wins over swim/flight head animation. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("RETURN"), require = 1)
    private void gravityengine$controllerHead(LivingEntity entity, float swing, float amount, float age,
            float yaw, float pitch, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var scope = cc.sighs.gravityengine.client.LivingAttitudeRenderContext.current();
        if (scope != null && scope.attitude() != null) {
            cc.sighs.gravityengine.client.ControllerHeadPresentation.apply(scope.attitude(), head, hat);
            gravityengine$ownedHead = true;
        }
    }

    @WrapOperation(method = "prepareMobModel(Lnet/minecraft/world/entity/LivingEntity;FFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getSwimAmount(F)F"), require = 1)
    private float gravityengine$modelSwimAmount(LivingEntity entity, float partialTick, Operation<Float> original) {
        return Math.max(original.call(entity, partialTick), ClientCharacterPresentation.swimAmount(entity, partialTick));
    }
    @WrapOperation(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isVisuallySwimming()Z"), require = 1)
    private boolean gravityengine$modelSwimming(LivingEntity entity, Operation<Boolean> original) {
        return ClientCharacterPresentation.swimAction(entity) || original.call(entity);
    }
}
