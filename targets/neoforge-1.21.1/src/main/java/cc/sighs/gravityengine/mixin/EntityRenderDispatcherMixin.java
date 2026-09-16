package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientGravityFrameSampler;
import cc.sighs.gravityengine.client.GravityDebugRenderGeometry;
import cc.sighs.gravityengine.client.GravityPresentationIntegration;
import cc.sighs.gravityengine.client.GravityRenderTransforms;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gravity-aware F3+B debug hitbox rendering for entities under the custom
 * presentation policy.
 *
 * <p>NeoForge 21.1.249 {@code EntityRenderDispatcher.renderHitbox} is a
 * private static method that assumes the eye is
 * {@code position + worldY * eyeHeight} and the view ray is
 * {@code entity.getViewVector(partialTick)}. Both assumptions are wrong for
 * GravityEngine: the eye lies along the presentation gravity frame's up axis
 * from the presentation feet anchor, the red eye-level rectangle is
 * perpendicular to that axis, and the blue ray must use the gravity-local
 * look transform. The method also assumes the enclosing AABB's world X/Z
 * extent for the eye rectangle, which is invalid for a rotated exact body.</p>
 *
 * <p>Targeted redirections would require ordinal-dependent intercepts of
 * individual {@code renderLineBox}/{@code renderVector} calls plus a new eye
 * origin, so this mixin instead cancels at {@code renderHitbox} HEAD and
 * replicates the 1.21.1 method body for custom-presentation client entities,
 * replacing only the gravity-sensitive red/blue sections. Every other entity
 * -- including the server-side magenta hitbox path, whose {@code Entity} lives
 * in a {@code ServerLevel} -- falls through to the untouched vanilla body.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    /**
     * Exact NeoForge 21.1.249 target:
     *
     * <pre>
     * private static void renderHitbox(
     *     PoseStack poseStack,
     *     VertexConsumer buffer,
     *     Entity p_entity,
     *     float red,      // world path: partialTicks
     *     float green,
     *     float blue,
     *     float alpha
     * )
     * </pre>
     *
     * <p>In the world path {@code EntityRenderDispatcher.render} invokes this
     * after restoring the render offset, so the PoseStack origin is the
     * vanilla interpolated render feet
     * {@code Mth.lerp(partialTick, xOld, getX())} relative to the camera.
     * That is the only call site this mixin replaces; the server-side overlay
     * passes a server entity whose level is not client-side.</p>
     */
    @Inject(
            method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;FFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private static void gravityengine$gravityDebugHitbox(
            PoseStack poseStack,
            VertexConsumer buffer,
            Entity entity,
            float partialTick,
            float green,
            float blue,
            float alpha,
            CallbackInfo ci
    ) {
        if (!entity.level().isClientSide
                || !GravityInfluencePolicy.usesCustomPresentation(entity)) {
            return;
        }
        gravityengine$renderCustomHitbox(
                poseStack,
                buffer,
                entity,
                partialTick,
                green,
                blue,
                alpha
        );
        ci.cancel();
    }

    /**
     * Faithful 1.21.1 {@code renderHitbox} replica for the custom path. The
     * white conservative AABB, multipart boxes and passenger/vehicle boxes
     * keep vanilla semantics exactly; only the eye plane and view ray are
     * replaced by gravity-aware geometry.
     */
    @Unique
    private static void gravityengine$renderCustomHitbox(
            PoseStack poseStack,
            VertexConsumer buffer,
            Entity entity,
            float partialTick,
            float green,
            float blue,
            float alpha
    ) {
        // White box: Entity#getBoundingBox() remains the conservative public
        // world-axis AABB; never rotate or reshape it here.
        AABB aabb = entity.getBoundingBox()
                .move(-entity.getX(), -entity.getY(), -entity.getZ());
        LevelRenderer.renderLineBox(
                poseStack, buffer, aabb, green, blue, alpha, 1.0F
        );

        if (entity.isMultipartEntity()) {
            double d0 = -Mth.lerp(
                    (double) partialTick, entity.xOld, entity.getX()
            );
            double d1 = -Mth.lerp(
                    (double) partialTick, entity.yOld, entity.getY()
            );
            double d2 = -Mth.lerp(
                    (double) partialTick, entity.zOld, entity.getZ()
            );

            for (net.neoforged.neoforge.entity.PartEntity<?> part
                    : entity.getParts()) {
                poseStack.pushPose();
                double d3 = d0 + Mth.lerp(
                        (double) partialTick, part.xOld, part.getX()
                );
                double d4 = d1 + Mth.lerp(
                        (double) partialTick, part.yOld, part.getY()
                );
                double d5 = d2 + Mth.lerp(
                        (double) partialTick, part.zOld, part.getZ()
                );
                poseStack.translate(d3, d4, d5);
                LevelRenderer.renderLineBox(
                        poseStack,
                        buffer,
                        part.getBoundingBox().move(
                                -part.getX(), -part.getY(), -part.getZ()
                        ),
                        0.25F,
                        1.0F,
                        0.0F,
                        1.0F
                );
                poseStack.popPose();
            }
        }

        Vec3 renderOrigin = GravityRenderTransforms.vanillaRenderOrigin(
                entity, partialTick
        );
        ClientGravityFrameSampler.RenderSnapshot snapshot =
                GravityPresentationIntegration.snapshot(entity, partialTick);
        // One presentation eye authority shared by the red plane center and
        // the blue ray origin: feet + frame.up * eyeHeight on the presentation
        // snapshot, identical to the picking eye position.
        Vec3 eyeCenter = GravityPresentationIntegration.pickingEyePosition(
                entity, partialTick
        );

        if (entity instanceof LivingEntity living) {
            GravityDebugRenderGeometry.EyePlane plane =
                    GravityDebugRenderGeometry.eyePlaneAt(
                            eyeCenter,
                            snapshot.frame(),
                            living.getDimensions(living.getPose()).width()
                    );
            gravityengine$renderEyePlane(poseStack, buffer, plane, renderOrigin);
        }

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            float f = Math.min(
                    vehicle.getBbWidth(), entity.getBbWidth()
            ) / 2.0F;
            Vec3 vec3 = vehicle.getPassengerRidingPosition(entity)
                    .subtract(entity.position());
            LevelRenderer.renderLineBox(
                    poseStack,
                    buffer,
                    vec3.x - (double) f,
                    vec3.y,
                    vec3.z - (double) f,
                    vec3.x + (double) f,
                    vec3.y + 0.0625,
                    vec3.z + (double) f,
                    1.0F,
                    1.0F,
                    0.0F,
                    1.0F
            );
        }

        gravityengine$renderViewRay(
                poseStack,
                buffer,
                eyeCenter,
                renderOrigin,
                GravityPresentationIntegration.viewVector(entity, partialTick)
        );
    }

    /**
     * Red eye-level rectangle. {@code plane} is world-space; the PoseStack
     * origin is the vanilla render origin, so each point is converted by
     * {@code local = world - renderOrigin} before vertex submission.
     */
    @Unique
    private static void gravityengine$renderEyePlane(
            PoseStack poseStack,
            VertexConsumer buffer,
            GravityDebugRenderGeometry.EyePlane plane,
            Vec3 renderOrigin
    ) {
        PoseStack.Pose pose = poseStack.last();
        gravityengine$addDebugEdge(
                buffer, pose, plane.a(), plane.b(), renderOrigin,
                EYE_PLANE_RED
        );
        gravityengine$addDebugEdge(
                buffer, pose, plane.b(), plane.c(), renderOrigin,
                EYE_PLANE_RED
        );
        gravityengine$addDebugEdge(
                buffer, pose, plane.c(), plane.d(), renderOrigin,
                EYE_PLANE_RED
        );
        gravityengine$addDebugEdge(
                buffer, pose, plane.d(), plane.a(), renderOrigin,
                EYE_PLANE_RED
        );
    }

    /**
     * Blue two-block view ray from the presentation eye along the
     * gravity-local look direction. Origin, direction and length follow the
     * 1.21.1 vanilla renderHitbox contract
     * ({@code getViewVector(partialTick).scale(2.0)}), with the gravity-aware
     * replacements the task requires.
     */
    @Unique
    private static void gravityengine$renderViewRay(
            PoseStack poseStack,
            VertexConsumer buffer,
            Vec3 originWorld,
            Vec3 renderOrigin,
            Vec3 directionWorld
    ) {
        /*
         * Match vanilla EntityRenderDispatcher.renderVector exactly:
         *
         *     vector = getViewVector(partialTick).scale(2.0)
         *
         * and use that SAME two-block vector for:
         *   1. endpoint displacement
         *   2. line vertex normal attribute
         *
         * Do not normalize it again here. RenderType.LINES consumes this
         * attribute as part of its line expansion semantics.
         */
        Vec3 vector = directionWorld.scale(
                GravityDebugRenderGeometry.VANILLA_DEBUG_RAY_LENGTH
        );

        Vec3 start = originWorld.subtract(renderOrigin);
        Vec3 end = start.add(vector);

        PoseStack.Pose pose = poseStack.last();

        buffer.addVertex(
                        pose,
                        (float) start.x,
                        (float) start.y,
                        (float) start.z
                )
                .setColor(VIEW_RAY_BLUE)
                .setNormal(
                        pose,
                        (float) vector.x,
                        (float) vector.y,
                        (float) vector.z
                );

        buffer.addVertex(
                        pose,
                        (float) end.x,
                        (float) end.y,
                        (float) end.z
                )
                .setColor(VIEW_RAY_BLUE)
                .setNormal(
                        pose,
                        (float) vector.x,
                        (float) vector.y,
                        (float) vector.z
                );
    }

    /** Emits one two-vertex line in PoseStack-local coordinates. */
    @Unique
    private static void gravityengine$addDebugEdge(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 fromWorld,
            Vec3 toWorld,
            Vec3 renderOrigin,
            float[] color
    ) {
        Vec3 from = fromWorld.subtract(renderOrigin);
        Vec3 to = toWorld.subtract(renderOrigin);
        Vec3 normal = to.subtract(from).normalize();
        buffer.addVertex(
                        pose,
                        (float) from.x,
                        (float) from.y,
                        (float) from.z
                )
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(
                        pose,
                        (float) normal.x,
                        (float) normal.y,
                        (float) normal.z
                );
        buffer.addVertex(
                        pose,
                        (float) to.x,
                        (float) to.y,
                        (float) to.z
                )
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(
                        pose,
                        (float) normal.x,
                        (float) normal.y,
                        (float) normal.z
                );
    }

    /** Vanilla F3+B eye-plane red (1, 0, 0, 1). */
    @Unique
    private static final float[] EYE_PLANE_RED = {
            1.0F, 0.0F, 0.0F, 1.0F
    };

    /** Vanilla F3+B view-ray blue ({@code -16776961}). */
    @Unique
    private static final int VIEW_RAY_BLUE = -16776961;
}
