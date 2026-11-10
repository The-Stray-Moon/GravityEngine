package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeRenderSnapshot;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Client-only integration boundary for gravity presentation consumers.
 *
 * <p>Camera, model rendering and debug rendering consume the same visual
 * timeline. Picking consumes the current semantic look and physical eye;
 * camera convergence never delays gameplay aim. When body attitude owns
 * presentation the camera consumes semantic control independently of the model's
 * accepted Qbody; when only gravity presentation is active
 * the look stays {@code GravityFrame * gravity-local yaw/pitch}.</p>
 */
public final class GravityPresentationIntegration {
    private GravityPresentationIntegration() {}

    public static ClientGravityFrameSampler.RenderSnapshot snapshot(Entity entity, float partialTick) {
        return ClientGravityFrameSampler.sample(Objects.requireNonNull(entity, "entity"), partialTick);
    }

    /**
     * Computes the custom-gravity camera origin from the presentation pose and
     * the Camera-owned visual eye height.
     *
     * <p>This is a presentation boundary only. {@code visualEyeHeight} must be
     * the Camera-smoothed value {@code Mth.lerp(partialTick,
     * Camera.eyeHeightOld, Camera.eyeHeight)}, never the instantaneous
     * {@code Entity#getEyeHeight()}; vanilla {@code Camera.setup} applies its
     * own eye-height smoothing independently of entity eye semantics.</p>
     *
     * @return {@code null} when the camera must keep the vanilla world-Y path.
     */
    @Nullable
    public static Vec3 cameraPosition(
            Entity entity,
            float partialTick,
            float visualEyeHeight
    ) {
        Objects.requireNonNull(entity, "entity");
        if (!usesCameraPresentation(entity)) return null;
        ClientGravityFrameSampler.RenderSnapshot gravity =
                snapshot(entity, partialTick);
        BodyAttitudeRenderSnapshot attitude = attitudeSnapshot(
                entity, partialTick);
        return presentationEyePosition(
                gravity, attitude, visualEyeHeight);
    }

    /** Pure camera-origin derivation for one render snapshot. */
    static Vec3 cameraPosition(
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            double visualEyeHeight
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return presentationEyePosition(snapshot, null, visualEyeHeight);
    }

    /** Pure BodyAttitude camera-origin derivation from one immutable pose pair. */
    static Vec3 attitudeCameraPosition(
            ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
            BodyAttitudeRenderSnapshot attitude,
            double visualEyeHeight
    ) {
        Objects.requireNonNull(attitude, "attitude");
        return presentationEyePosition(
                gravitySnapshot, attitude, visualEyeHeight);
    }

    /** Pure visual eye derivation used by Camera. */
    static Vec3 presentationEyePosition(
            ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
            @Nullable BodyAttitudeRenderSnapshot attitude,
            double visualEyeHeight
    ) {
        Objects.requireNonNull(gravitySnapshot, "gravitySnapshot");
        return GravityRenderTransforms.presentationEyePosition(
                gravitySnapshot, attitude, visualEyeHeight);
    }

    /**
     * Computes the single authoritative camera orientation.
     *
     * <p>With a render-time body-attitude snapshot:
     *
     * <pre>
     * Qcamera = Qbody * QbodyRelativeView * QcameraModifier
     * </pre>
     *
     * The first Camera.setup rotation carries NeoForge event angles. Those are
     * split into the event's interpolated scalar defaults and a camera-only
     * modifier; explicit pending player input is resolved independently of
     * those defaults. Without an attitude snapshot the gravity-frame path is
     * used.</p>
     */
    public static GravityCameraOrientationInstaller.CameraOrientationState
    cameraOrientationFromSetup(
            Entity entity,
            float partialTick,
            float eventYaw,
            float eventPitch,
            float roll
    ) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player player) {
            BodyAttitudeRenderSnapshot attitude =
                    BodyRenderPoseResolver.snapshot(player, partialTick);
            if (attitude != null) {
                BodyAttitudeRenderLookResolver.RenderLookSample playerLook =
                        BodyAttitudeRenderLookResolver.resolve(attitude);
                BodyAttitudeRenderLookResolver.CameraLookSample cameraLook =
                        BodyAttitudeRenderLookResolver.applyCameraModifier(
                                playerLook,
                                player.getViewYRot(partialTick),
                                player.getViewXRot(partialTick),
                                eventYaw,
                                eventPitch);
                AttitudeSpaceTransform.LocalLookAngles local =
                        cameraLook.finalLocalLook();
                if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "camera-attitude-resolve",
                        "partialTick=%s eventYaw=%s eventPitch=%s eventRoll=%s qDisplay=%s playerLook=%s cameraLook=%s",
                        partialTick, eventYaw, eventPitch, roll, attitude.worldFromBody(), playerLook, cameraLook);
                return GravityCameraOrientationInstaller.computeAttitude(
                        attitude.cameraView().worldFromController(),
                        local.yawDegrees(),
                        local.pitchDegrees(),
                        roll
                );
            }
        }
        var gravity = snapshot(entity, partialTick);
        if (PlayerViewDebugLog.shouldLog(entity)) PlayerViewDebugLog.event(entity, "camera-gravity-resolve",
                "partialTick=%s eventYaw=%s eventPitch=%s eventRoll=%s gravity=%s",
                partialTick, eventYaw, eventPitch, roll, gravity);
        return GravityCameraOrientationInstaller.compute(
                gravity.frame(),
                eventYaw,
                eventPitch,
                roll
        );
    }

    /**
     * Installs a later Camera.setup transform, such as mirrored third person.
     * Its arguments were derived from the already-installed camera-local
     * Vanilla-facing scalars and must not pass through player-input rebasing.
     */
    public static GravityCameraOrientationInstaller.CameraOrientationState
    cameraOrientationFromLocal(
            Entity entity,
            float partialTick,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player player) {
            BodyAttitudeRenderSnapshot attitude =
                    BodyRenderPoseResolver.snapshot(player, partialTick);
            if (attitude != null) {
                return GravityCameraOrientationInstaller.computeAttitude(
                        attitude.cameraView().worldFromController(),
                        localYaw,
                        localPitch,
                        localRoll);
            }
        }
        return GravityCameraOrientationInstaller.compute(
                snapshot(entity, partialTick).frame(),
                localYaw,
                localPitch,
                localRoll);
    }

    public static boolean applySetupRotationsForRender(
            LivingEntity entity,
            float partialTick,
            float scale,
            PoseStack poseStack
    ) {
        // Exactly one body-attitude read belongs to this render invocation.
        BodyAttitudeRenderSnapshot attitude = entity instanceof Player player
                ? BodyRenderPoseResolver.snapshot(player, partialTick)
                : null;
        return applySetupRotationsForRender(
                attitude, entity, partialTick, scale, poseStack);
    }

    /**
     * Convenience variant for callers that do not already own a gravity
     * snapshot; it samples the gravity frame once and delegates.
     */
    public static boolean applySetupRotationsForRender(
            @Nullable BodyAttitudeRenderSnapshot attitude,
            LivingEntity entity,
            float partialTick,
            float scale,
            PoseStack poseStack
    ) {
        return applySetupRotationsForRender(
                snapshot(entity, partialTick),
                attitude, entity, partialTick, scale, poseStack);
    }

    /**
     * Snapshot-explicit renderer path.  The caller owns the single immutable
     * gravity snapshot for this render invocation; this method never calls
     * {@code snapshot(entity, partialTick)} itself, so feet, gravity frame and
     * body attitude are all sampled from the same instant.
     */
    public static boolean applySetupRotationsForRender(
            ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
            @Nullable BodyAttitudeRenderSnapshot attitude,
            LivingEntity entity,
            float partialTick,
            float scale,
            PoseStack poseStack
    ) {
        Objects.requireNonNull(gravitySnapshot, "gravitySnapshot");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(poseStack, "poseStack");
        boolean customAnchor = ClientGravityFrameSampler.usesPresentation(entity);
        if (attitude != null) {
            Vec3 attitudeFeet = GravityRenderTransforms.attitudePresentationFeet(
                    gravitySnapshot, attitude);
            GravityRenderTransforms.applyPresentationAnchorTranslationAfterScale(
                    poseStack, attitudeFeet, entity, partialTick, scale);
            GravityRenderTransforms.applyAttitudeBodyRotation(poseStack, attitude);
            if (entity.isFullyFrozen()) {
                GravityRenderTransforms.applyShakingDecoration(
                        poseStack, entity.tickCount);
            }
            return true;
        }
        if (customAnchor) {
            GravityRenderTransforms.applyPresentationAnchorTranslationAfterScale(
                    poseStack, gravitySnapshot, entity, partialTick, scale);
            GravityRenderTransforms.applyGravityBodyRotation(
                    poseStack, gravitySnapshot);
        }
        return false;
    }

    /** Replaces the block-ray part of GameRenderer picking when attitude or gravity presentation is active. */
    public static HitResult pickBlock(
            Entity entity,
            double distance,
            float partialTick,
            boolean includeFluids
    ) {
        Objects.requireNonNull(entity, "entity");
        if (!usesPickingPresentation(entity)) {
            return entity.pick(distance, partialTick, includeFluids);
        }

        Vec3 start = pickingEyePosition(entity, partialTick);
        Vec3 end = start.add(viewVector(entity, partialTick).scale(distance));
        return entity.level().clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
                entity
        ));
    }

    /** Supplies the entity-ray direction used by GameRenderer picking. */
    public static Vec3 viewVector(Entity entity, float partialTick) {
        Objects.requireNonNull(entity, "entity");
        if (!cc.sighs.gravityengine.look.PlayerLookIntegration.usesGravityEngineLook(entity)) {
            return entity.getViewVector(partialTick);
        }
        return MinecraftMathAdapter.toMinecraft(
                cc.sighs.gravityengine.look.PlayerLookIntegration.capture(
                        entity, GravityFrameAccess.authoritativeFrame(entity)).forward());
    }

    public static Vec3 pickingEyePosition(Entity entity, float partialTick) {
        return pickingEyePosition(entity, partialTick, entity.getEyePosition(partialTick));
    }

    public static Vec3 pickingEyePosition(Entity entity, float partialTick, Vec3 underlyingEyePosition) {
        Objects.requireNonNull(entity, "entity");
        return Objects.requireNonNull(underlyingEyePosition, "underlyingEyePosition");
    }

    @Nullable
    public static CollisionBody debugCollisionBody(Entity entity, float partialTick) {
        Objects.requireNonNull(entity, "entity");
        if (!GravityInfluencePolicy.usesCustomCollision(entity)) return null;
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        return body(snapshot(entity, partialTick), dimensions);
    }

    /**
     * The solver's actual physical collision body under the currently
     * installed authoritative frame.  This is the body the movement solver
     * sweeps; it is deliberately distinct from the interpolated presentation
     * body returned by {@link #debugCollisionBody}.
     */
    @Nullable
    public static CollisionBody physicalCollisionBody(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!GravityInfluencePolicy.usesCustomCollision(entity)) return null;
        // Physical geometry reads its installed axis; presentation attitude cannot select it.
        return GravityEntityGeometry.body(entity);
    }

    static Vec3 eyePosition(
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            double eyeHeight
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return presentationEyePosition(snapshot, null, eyeHeight);
    }

    static Vec3 viewVector(
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            float viewYRot,
            float viewXRot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return MinecraftMathAdapter.toMinecraft(
                GravityLocalLook.toWorld(
                snapshot.frame(),
                viewYRot,
                viewXRot
                ).forward()
        );
    }

    static CollisionBody body(
            ClientGravityFrameSampler.RenderSnapshot snapshot,
            EntityDimensions dimensions
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return OrientedBox.fromDimensions(
                MinecraftMathAdapter.toVec3d(
                        snapshot.center()
                ),
                dimensions.width(),
                dimensions.height(),
                snapshot.frame().orientation()
        );
    }

    public static boolean usesCameraPresentation(Entity entity) {
        if (entity instanceof LivingEntity living && living.isSleeping()) {
            return false;
        }
        if (entity instanceof Player player
                && BodyRenderPoseResolver.hasAttitudePresentation(player)) {
            return true;
        }
        return ClientGravityFrameSampler.usesPresentation(entity);
    }

    private static boolean usesPickingPresentation(Entity entity) {
        return cc.sighs.gravityengine.look.PlayerLookIntegration.usesGravityEngineLook(entity);
    }

    @Nullable
    private static BodyAttitudeRenderSnapshot attitudeSnapshot(
            Entity entity,
            float partialTick
    ) {
        return entity instanceof Player player
                ? BodyRenderPoseResolver.snapshot(player, partialTick)
                : null;
    }

    /** Which system currently owns the entity's final global presentation. */
    public static PresentationAuthority presentationAuthority(Entity entity) {
        if (entity instanceof Player player
                && BodyRenderPoseResolver.hasAttitudePresentation(player)) {
            return PresentationAuthority.BODY_ATTITUDE;
        }
        if (ClientGravityFrameSampler.usesPresentation(entity)) {
            return PresentationAuthority.GRAVITY_FRAME;
        }
        return PresentationAuthority.VANILLA;
    }
}
