package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

public final class GravityRenderTransforms {
    private GravityRenderTransforms() {}

    public static void applyPresentationAnchorTranslation(
            PoseStack poseStack,
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            LivingEntity entity,
            float partialTick
    ) {
        Vec3 vanillaFeet = vanillaRenderOrigin(entity, partialTick);
        Vec3 correction = snapshot.feet().subtract(vanillaFeet);
        poseStack.translate(correction.x, correction.y, correction.z);
    }

    /** Same anchor correction when LivingEntityRenderer has already applied uniform scale. */
    public static void applyPresentationAnchorTranslationAfterScale(
            PoseStack poseStack,
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            LivingEntity entity,
            float partialTick,
            float scale
    ) {
        applyPresentationAnchorTranslationAfterScale(
                poseStack, snapshot.feet(), entity, partialTick, scale);
    }

    /** Anchor correction for an explicitly derived presentation root. */
    public static void applyPresentationAnchorTranslationAfterScale(
            PoseStack poseStack,
            Vec3 presentationRoot,
            LivingEntity entity,
            float partialTick,
            float scale
    ) {
        if (!Float.isFinite(scale) || Math.abs(scale) <= 1.0E-8F) {
            throw new IllegalArgumentException("render scale must be finite and non-zero");
        }
        Vec3 correction = presentationRoot.subtract(
                vanillaRenderOrigin(entity, partialTick));
        poseStack.translate(
                correction.x / scale,
                correction.y / scale,
                correction.z / scale);
    }

    /**
     * Feet/root implied by one absolute body attitude while preserving the
     * body center derived from Vanilla's interpolated position anchor.
     */
    public static Vec3 attitudePresentationFeet(
            ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
            BodyAttitudeRenderSnapshot attitude
    ) {
        Vec3 bodyUp = AttitudeSpaceTransform.bodyUpWorld(
                attitude.worldFromBody());
        return gravitySnapshot.center().subtract(
                bodyUp.scale(gravitySnapshot.presentationHalfHeight()));
    }

    /** One coherent body-centered eye formula shared by camera and picking. */
    public static Vec3 presentationEyePosition(
            ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
            BodyAttitudeRenderSnapshot attitude,
            double visualEyeHeight
    ) {
        if (!Double.isFinite(visualEyeHeight)) {
            throw new IllegalArgumentException("visualEyeHeight must be finite");
        }
        if (attitude == null) {
            return gravitySnapshot.feet().add(
                    gravitySnapshot.frame().up().scale(visualEyeHeight));
        }
        Vec3 bodyUp = AttitudeSpaceTransform.bodyUpWorld(
                attitude.worldFromBody());
        return gravitySnapshot.center().add(bodyUp.scale(
                visualEyeHeight
                        - gravitySnapshot.presentationHalfHeight()));
    }

    public static void applyGravityBodyRotation(
            PoseStack poseStack,
            ClientGravityFrameSampler.RenderSnapshot snapshot
    ) {
        poseStack.mulPose(snapshot.rotation().quaternion());
    }

    /**
     * Installs the absolute canonical body basis and the one renderer-only
     * Minecraft model baseline correction.  For default gravity this is
     * {@code rotationY(-bodyYaw) * rotationY(180 degrees)}, exactly vanilla's
     * {@code rotationY(180 - bodyYaw)}.
     */
    public static void applyAttitudeBodyRotation(
            PoseStack poseStack,
            BodyAttitudeRenderSnapshot snapshot
    ) {
        poseStack.mulPose(attitudeModelRotation(snapshot));
    }

    public static Quaternionf attitudeModelRotation(
            BodyAttitudeRenderSnapshot snapshot
    ) {
        Quaterniond attitude = snapshot.worldFromBody();
        Quaternionf modelFromCanonical = new Quaternionf().rotationY((float) Math.PI);
        return new Quaternionf(
                (float) attitude.x, (float) attitude.y,
                (float) attitude.z, (float) attitude.w)
                .normalize()
                .mul(modelFromCanonical)
                .normalize();
    }

    /**
     * Vanilla's ordinary freezing shake, expressed as a body-local decoration
     * after the absolute attitude. Vanilla adds this value to body yaw, hence
     * the negative local-Y rotation here.
     */
    public static Quaternionf shakingDecoration(int entityTickCount) {
        float yawDeltaDegrees = (float) (
                Math.cos((double) entityTickCount * 3.25D) * Math.PI * 0.4D);
        return new Quaternionf().rotationY(
                (float) Math.toRadians(-yawDeltaDegrees));
    }

    public static void applyShakingDecoration(PoseStack poseStack, int entityTickCount) {
        poseStack.mulPose(shakingDecoration(entityTickCount));
    }

    /**
     * The PoseStack origin installed by {@code EntityRenderDispatcher.render}
     * before any entity renderer or debug geometry is emitted: the vanilla
     * interpolated entity position {@code Mth.lerp(partialTick, xOld, x)}.
     *
     * <p>This is a renderer origin, not the physical or presentation feet
     * anchor: debug hitbox geometry is submitted relative to this point even
     * when the presentation body or bounding box uses a different world
     * anchor. Shared by the living renderer transform and the F3+B debug
     * mixin so both convert to the identical coordinate space.</p>
     */
    public static Vec3 vanillaRenderOrigin(Entity entity, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ())
        );
    }

}
