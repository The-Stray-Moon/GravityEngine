package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.client.BodyRenderPoseResolver;
import cc.sighs.gravityengine.client.ClientGravityFrameSampler;
import cc.sighs.gravityengine.client.GravityPresentationIntegration;
import cc.sighs.gravityengine.client.LivingAttitudeRenderContext;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import javax.annotation.Nullable;

/**
 * Render-scoped seams for one {@code LivingEntityRenderer.render} invocation:
 *
 * <ul>
 *   <li>the whole render runs inside a push/pop
 *       {@link LivingAttitudeRenderContext} so nested renders cannot leak;</li>
 *   <li>{@code setupRotations} installs the absolute body-attitude rotation
 *       (single snapshot sample) and records it in the context;</li>
 *   <li>the {@code EntityModel.setupAnim} call and every
 *       {@code RenderLayer.render} call receive model-local head yaw/pitch
 *       relative to the current body instead of vanilla body-relative head
 *       scalars.</li>
 * </ul>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity> {
    /** Descriptor verified against the 21.1.249 merged jar. */
    private static final String RENDER_DESCRIPTOR =
            "render(Lnet/minecraft/world/entity/LivingEntity;"
                    + "FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @WrapMethod(method = RENDER_DESCRIPTOR, require = 1)
    private void gravityengine$renderScoped(
            LivingEntity entity,
            float bob,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            Operation<Void> original
    ) {
        LivingAttitudeRenderContext.Scope scope =
                LivingAttitudeRenderContext.push();
        try {
            original.call(
                    entity, bob, partialTick, poseStack, buffer, packedLight
            );
        } finally {
            LivingAttitudeRenderContext.pop();
        }
    }

    /** Exact 1.21.1 virtual call: one render owns one attitude snapshot. */
    @WrapOperation(
            method = RENDER_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V"
            ),
            require = 1
    )
    private void gravityengine$setupRotationsOnce(
            LivingEntityRenderer<?, ?> renderer,
            LivingEntity entity,
            PoseStack poseStack,
            float bob,
            float bodyYaw,
            float partialTick,
            float scale,
            Operation<Void> original
    ) {
        LivingAttitudeRenderContext.Scope scope =
                LivingAttitudeRenderContext.current();
        boolean replaced = false;
        if (scope != null) {
            cc.sighs.gravityengine.client.BodyAttitudeRenderSnapshot attitude =
                    readAttitude(entity, partialTick);
            if (attitude != null
                    || GravityInfluencePolicy
                    .usesCustomPresentation(entity)) {
                // One immutable gravity snapshot for the whole render; the
                // snapshot-explicit overload never samples again.
                ClientGravityFrameSampler.RenderSnapshot gravitySnapshot =
                        GravityPresentationIntegration.snapshot(
                                entity, partialTick);
                scope.recordPresentation(
                        gravitySnapshot, attitude, cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess
                                .cast(entity).gravityengine$getMaxHeadRotationRelativeToBody());
                replaced = GravityPresentationIntegration
                        .applySetupRotationsForRender(
                                gravitySnapshot, attitude, entity,
                                partialTick, scale, poseStack);
            }
        }
        if (!replaced) {
            original.call(renderer, entity, poseStack, bob,
                    bodyYaw, partialTick, scale);
        }
    }

    /**
     * Replaces the netHeadYaw/headPitch locals passed to {@code setupAnim}
     * with model-local look angles relative to the active body quaternion.
     * Without an attitude the original floats pass through unchanged.
     */
    @WrapOperation(
            method = RENDER_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"
            ),
            require = 1
    )
    private void gravityengine$modelSetupAnim(
            EntityModel<?> model,
            Entity entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            Operation<Void> original
    ) {
        if (!(entity instanceof LivingEntity living)) {
            original.call(model, entity, limbSwing, limbSwingAmount,
                    ageInTicks, netHeadYaw, headPitch);
            return;
        }
        AttitudeSpaceTransform.LocalLookAngles look =
                modelLookFor(living, netHeadYaw, headPitch);
        if (look == null) {
            original.call(model, entity, limbSwing, limbSwingAmount,
                    ageInTicks, netHeadYaw, headPitch);
            return;
        }
        original.call(model, entity, limbSwing, limbSwingAmount,
                ageInTicks, look.yawDegrees(), look.pitchDegrees());
    }

    /**
     * Same corrected model-local angles for every {@code RenderLayer.render};
     * third-party layers must not see a different head joint semantic than
     * the main model.
     */
    @WrapOperation(
            method = RENDER_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"
            ),
            require = 1
    )
    private void gravityengine$layerRender(
            RenderLayer<?, ?> layer,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            Entity entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            Operation<Void> original
    ) {
        if (!(entity instanceof LivingEntity living)) {
            original.call(layer, poseStack, buffer, packedLight, entity,
                    limbSwing, limbSwingAmount, partialTick, ageInTicks,
                    netHeadYaw, headPitch);
            return;
        }
        AttitudeSpaceTransform.LocalLookAngles look =
                modelLookFor(living, netHeadYaw, headPitch);
        if (look == null) {
            original.call(layer, poseStack, buffer, packedLight, entity,
                    limbSwing, limbSwingAmount, partialTick, ageInTicks,
                    netHeadYaw, headPitch);
            return;
        }
        original.call(layer, poseStack, buffer, packedLight, entity,
                limbSwing, limbSwingAmount, partialTick, ageInTicks,
                look.yawDegrees(), look.pitchDegrees());
    }

    /**
     * Model-local look for this render, or {@code null} when no attitude is
     * active (the original floats stay untouched in that case).
     */
    @Nullable
    private static AttitudeSpaceTransform.LocalLookAngles modelLookFor(
            LivingEntity entity,
            float netHeadYaw,
            float headPitch
    ) {
        LivingAttitudeRenderContext.Scope scope =
                LivingAttitudeRenderContext.current();
        if (scope == null) return null;
        AttitudeSpaceTransform.LocalLookAngles look =
                scope.modelLook(netHeadYaw, headPitch);
        return scope.attitude() == null ? null : look;
    }

    @Nullable
    private static cc.sighs.gravityengine.client.BodyAttitudeRenderSnapshot
    readAttitude(LivingEntity entity, float partialTick) {
        return entity instanceof Player player
                ? BodyRenderPoseResolver.snapshot(
                        player, partialTick)
                : null;
    }
}
