package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Quaternion-authoritative camera orientation.
 *
 * <p>NeoForge 21.1.249's {@code Camera.setRotation(float, float, float)} is the
 * single unified boundary that installs the rotation quaternion and derives
 * {@code forwards}/{@code up}/{@code left}. We intercept that boundary so the
 * gravity-local look triple is composed as
 * {@code QgravityFrame * QlocalYaw * QlocalPitch * Qmodifier} and installed as
 * the camera authority, rather than being flattened into world Y-X-Z Euler and
 * reconstructed. For default gravity no hook runs, so vanilla behavior is
 * unchanged.</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin implements CameraEyeHeightAccess {
    @Shadow @Nullable private Entity entity;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float roll;
    /**
     * Camera-owned eye-height smoothing. {@code Camera.tick()} advances these
     * toward {@code entity.getEyeHeight()} once per game tick at half rate;
     * {@code Camera.setup()} interpolates between them with the render partial
     * tick. GravityEngine must consume this state rather than the instantaneous
     * entity eye height so crouch/uncrouch and pose transitions keep vanilla
     * smoothing semantics.
     */
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow protected abstract void setPosition(Vec3 p);
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Unique private boolean gravityengine$insideSetup;
    @Unique private int gravityengine$setupRotationIndex;

    /**
     * Gives setRotation an explicit call-site semantic. The first setup call
     * contains absolute NeoForge event angles; later calls (mirrored third
     * person) are camera-local transforms of the state already installed.
     */
    @WrapMethod(
            method = "setup(Lnet/minecraft/world/level/BlockGetter;"
                    + "Lnet/minecraft/world/entity/Entity;ZZF)V",
            require = 1
    )
    private void gravityengine$setupRotationScope(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            Operation<Void> original
    ) {
        this.gravityengine$insideSetup = true;
        this.gravityengine$setupRotationIndex = 0;
        if (PlayerViewDebugLog.ENABLED) PlayerViewDebugLog.event(entity, "camera-setup-before",
                "detached=%s reverse=%s partialTick=%s", detached, thirdPersonReverse, partialTick);
        try {
            original.call(
                    level, entity, detached, thirdPersonReverse, partialTick);
        } finally {
            gravityengine$traceCamera(entity, "camera-setup-after");
            this.gravityengine$insideSetup = false;
            this.gravityengine$setupRotationIndex = 0;
        }
    }

    /** Observe the unified NeoForge rotation installer, including Vanilla fallback
     * and our cancellable quaternion install. Never resolve another render sample. */
    @WrapMethod(method = "setRotation(FFF)V", require = 1)
    private void gravityengine$traceRotation(float yaw, float pitch, float roll, Operation<Void> original) {
        if (!PlayerViewDebugLog.ENABLED) { original.call(yaw, pitch, roll); return; }
        try (var trace = PlayerViewDebugLog.begin(this.entity, "camera-rotation",
                "requestedYaw=%s requestedPitch=%s requestedRoll=%s insideSetup=%s rotationIndex=%s",
                yaw, pitch, roll, this.gravityengine$insideSetup, this.gravityengine$setupRotationIndex)) {
            gravityengine$traceCamera(this.entity, "camera-output-before");
            try {
                original.call(yaw, pitch, roll);
            } finally {
                gravityengine$traceCamera(this.entity, "camera-output-after");
            }
        }
    }

    @Unique
    private void gravityengine$traceCamera(Entity actor, String event) {
        if (!PlayerViewDebugLog.ENABLED) return;
        Camera camera = (Camera) (Object) this;
        PlayerViewDebugLog.event(actor, event,
                "camera=%s partialTick=%s yaw=%s pitch=%s roll=%s qCamera=%s forward=%s up=%s left=%s position=%s",
                Integer.toHexString(System.identityHashCode(camera)), camera.getPartialTickTime(),
                this.yRot, this.xRot, this.roll, camera.rotation(), camera.getLookVector(), camera.getUpVector(),
                camera.getLeftVector(), camera.getPosition());
    }

    @Override
    public float gravityengine$visualEyeHeight(float partialTick) {
        return Mth.lerp(partialTick, this.eyeHeightOld, this.eyeHeight);
    }

    /**
     * Intercepts every {@code setRotation} so the gravity-local look triple is
     * installed as a quaternion. Skipping the vanilla body keeps {@code xRot},
     * {@code yRot}, {@code roll} as gravity-local scalars, which makes the
     * detached front view re-set (yaw+180, -pitch, -roll) commute correctly.
     */
    @Inject(method = "setRotation(FFF)V", at = @At("HEAD"), cancellable = true)
    private void gravityengine$installLook(float yRot, float xRot, float roll, CallbackInfo ci) {
        boolean initialSetupRotation = this.gravityengine$insideSetup
                && this.gravityengine$setupRotationIndex++ == 0;
        Entity entity = this.entity;
        if (entity == null
                || !GravityPresentationIntegration.usesCameraPresentation(entity)) {
            return;
        }
        GravityCameraOrientationInstaller.CameraOrientationState state =
                initialSetupRotation
                        ? GravityPresentationIntegration.cameraOrientationFromSetup(
                        entity,
                        ((Camera) (Object) this).getPartialTickTime(),
                        yRot, xRot, roll)
                        : GravityPresentationIntegration.cameraOrientationFromLocal(
                        entity,
                        ((Camera) (Object) this).getPartialTickTime(),
                        yRot, xRot, roll);
        gravityengine$installOrientationState(state);
        if (GravityDebugLog.ENABLED) {
            gravityengine$logLook(
                    entity,
                    ((Camera) (Object) this).getPartialTickTime(),
                    yRot,
                    xRot,
                    initialSetupRotation,
                    state
            );
        }
        ci.cancel();
    }

    /**
     * The single orientation commit. It updates the quaternion, the three
     * derived camera basis vectors, and the gravity-local Vanilla-facing scalars
     * in one place.
     */
    private void gravityengine$installOrientationState(
            GravityCameraOrientationInstaller.CameraOrientationState state
    ) {
        Quaternionf rotation = ((Camera) (Object) this).rotation();
        rotation.set(state.rotation());
        Vector3f forwards = ((Camera) (Object) this).getLookVector();
        forwards.set(state.forwards());
        Vector3f up = ((Camera) (Object) this).getUpVector();
        up.set(state.up());
        Vector3f left = ((Camera) (Object) this).getLeftVector();
        left.set(state.left());
        this.xRot = state.xRot();
        this.yRot = state.yRot();
        this.roll = state.roll();
    }

    private static void gravityengine$logLook(
            Entity entity,
            float partialTick,
            float localYaw,
            float localPitch,
            boolean initialSetupRotation,
            GravityCameraOrientationInstaller.CameraOrientationState state
    ) {
        if (initialSetupRotation && entity instanceof Player player) {
            BodyAttitudeRenderSnapshot attitude =
                    BodyRenderPoseResolver.snapshot(
                            player, partialTick);
            if (attitude != null) {
                BodyAttitudeRenderLookResolver.RenderLookSample playerLook =
                        BodyAttitudeRenderLookResolver.resolve(
                                attitude);
                BodyAttitudeRenderLookResolver.CameraLookSample cameraLook =
                        BodyAttitudeRenderLookResolver.applyCameraModifier(
                                playerLook,
                                player.getViewYRot(partialTick),
                                player.getViewXRot(partialTick),
                                localYaw,
                                localPitch);
                Vec3 coherentWorldAim =
                        cc.sighs.gravityengine.attitude.AttitudeSpaceTransform
                                .worldLookFromBodyAngles(
                                        attitude.cameraView().worldFromController(),
                                        playerLook.localLook());
                double aimErrorDegrees = angleDegrees(
                        coherentWorldAim,
                        attitude.semanticWorldForward());
                GravityDebugLog.log(
                        entity,
                        "camera-attitude-look",
                        "pt=%.4f "
                                + "rawYaw=%.3f rawPitch=%.3f "
                                + "requestedWorldAim=%s "
                                + "coherentLocalYaw=%.3f coherentLocalPitch=%.3f "
                                + "cameraModifierYaw=%.3f "
                                + "cameraModifierPitch=%.3f "
                                + "finalLocalYaw=%.3f finalLocalPitch=%.3f "
                                + "coherentWorldAim=%s playerWorldAim=%s "
                                + "renderAimErrorDeg=%.7f "
                                + "qbody=%s qcamera=%s",
                        partialTick,
                        player.getYRot(),
                        player.getXRot(),
                        GravityDebugLog.vec(attitude.semanticWorldForward()),
                        playerLook.localLook().yawDegrees(),
                        playerLook.localLook().pitchDegrees(),
                        cameraLook.modifierYaw(),
                        cameraLook.modifierPitch(),
                        cameraLook.finalLocalLook().yawDegrees(),
                        cameraLook.finalLocalLook().pitchDegrees(),
                        GravityDebugLog.vec(coherentWorldAim),
                        GravityDebugLog.vec(playerLook.worldLook()),
                        aimErrorDegrees,
                        attitude.worldFromBody(),
                        state.rotation());
                return;
            }
        }
        var snapshot = cc.sighs.gravityengine.client.ClientGravityFrameSampler.sample(
                entity,
                partialTick
        );
        Quaternionf expected = GravityLocalLook.cameraQuaternion(
                snapshot.frame(),
                localYaw,
                localPitch,
                state.roll()
        );
        float error = (float) Math.toDegrees(
                2.0D * Math.acos(Math.min(1.0F, Math.abs(
                        new Quaternionf(expected).conjugate().mul(state.rotation()).w
                )))
        );
        GravityDebugLog.log(
                entity,
                "camera-look",
                "pt=%.4f localYaw=%.3f localPitch=%.3f roll=%.3f frameDown=%s frameUp=%s qcamera=%s expected=%s orientationError=%.5f forward=%s up=%s left=%s",
                partialTick,
                localYaw,
                localPitch,
                state.roll(),
                GravityDebugLog.vec(snapshot.frame().down()),
                GravityDebugLog.vec(snapshot.frame().up()),
                state.rotation(),
                expected,
                error,
                GravityDebugLog.vec(new Vec3(
                        state.forwards().x,
                        state.forwards().y,
                        state.forwards().z
                )),
                GravityDebugLog.vec(new Vec3(state.up().x, state.up().y, state.up().z)),
                GravityDebugLog.vec(new Vec3(state.left().x, state.left().y, state.left().z))
        );
    }

    private static double angleDegrees(Vec3 first, Vec3 second) {
        double denominator = Math.sqrt(first.lengthSqr() * second.lengthSqr());
        if (!(denominator > 0.0D)) return 0.0D;
        double cosine = Mth.clamp(first.dot(second) / denominator, -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(cosine));
    }

    /**
     * Replaces the single world-Y eye position write in {@code setup} with the
     * gravity-relative visual eye point.
     *
     * <p>Vanilla {@code Camera.setup} composes {@code lerp(partialTick,
     * eyeHeightOld, eyeHeight)} on world Y after the entity position; those
     * two fields are the Camera-owned smoothing state advanced by
     * {@code Camera.tick()}. The custom path keeps that Camera ownership and
     * only replaces the world-axis assumption: the visual eye height is
     * projected along the presentation frame's up axis from the rigid
     * presentation feet anchor. Running before the detached back-off keeps
     * third-person translation derived from the already-installed
     * orientation.</p>
     */
    @Redirect(
            method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V")
    )
    private void gravityengine$gravityEyePosition(
            Camera camera,
            double x,
            double y,
            double z
    ) {
        Entity entity = camera.getEntity();
        if (entity == null
                || !GravityPresentationIntegration.usesCameraPresentation(entity)) {
            this.setPosition(x, y, z);
            return;
        }
        float partialTick = camera.getPartialTickTime();
        float visualEyeHeight = gravityengine$visualEyeHeight(partialTick);
        Vec3 eye = GravityPresentationIntegration.cameraPosition(
                entity, partialTick, visualEyeHeight
        );
        if (eye == null) {
            this.setPosition(x, y, z);
            return;
        }
        this.setPosition(eye.x, eye.y, eye.z);
        if (GravityDebugLog.ENABLED) {
            gravityengine$logCameraPosition(
                    entity, partialTick, visualEyeHeight, eye
            );
        }
    }

    private void gravityengine$logCameraPosition(
            Entity entity,
            float partialTick,
            float visualEyeHeight,
            Vec3 cameraPosition
    ) {
        try {
            Camera self = (Camera) (Object) this;
            ClientGravityFrameSampler.RenderSnapshot snapshot =
                    ClientGravityFrameSampler.sample(entity, partialTick);
            var authoritative = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                    .authoritativeFrame(entity);
            Vec3 physicalCenter = cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.bodyCenter(entity, authoritative);
            // Translation follows Vanilla history even if no reference frame was
            // sampled during creative flight. Log that carrier directly.
            Vec3 previousPosition = new Vec3(entity.xo, entity.yo, entity.zo);
            Vec3 renderPosition = snapshot.center().subtract(
                    0.0D, snapshot.presentationHalfHeight(), 0.0D);
            GravityDebugLog.log(
                    entity,
                    "camera-position",
                    "pose=%s positionAnchor=%s physicalCenter=%s "
                            + "physicalFrameDown=%s physicalFrameUp=%s "
                            + "previousPositionAnchor=%s "
                            + "renderPositionAnchor=%s "
                            + "renderCenter=%s renderFeet=%s "
                            + "renderFrameDown=%s renderFrameUp=%s "
                            + "entityEyeHeight=%.5f cameraEyeOld=%.5f "
                            + "cameraEye=%.5f visualEye=%.5f "
                            + "camera=%s qCamera=%s cameraForward=%s "
                            + "cameraUp=%s cameraLeft=%s pt=%.4f "
                            + "revision=%d fallback=%s snapshotTick=%d",
                    entity.getPose(),
                    GravityDebugLog.vec(entity.position()),
                    GravityDebugLog.vec(physicalCenter),
                    GravityDebugLog.vec(authoritative.down()),
                    GravityDebugLog.vec(authoritative.up()),
                    GravityDebugLog.vec(previousPosition),
                    GravityDebugLog.vec(renderPosition),
                    GravityDebugLog.vec(snapshot.center()),
                    GravityDebugLog.vec(snapshot.feet()),
                    GravityDebugLog.vec(snapshot.frame().down()),
                    GravityDebugLog.vec(snapshot.frame().up()),
                    entity.getEyeHeight(),
                    this.eyeHeightOld,
                    this.eyeHeight,
                    visualEyeHeight,
                    GravityDebugLog.vec(cameraPosition),
                    self.rotation(),
                    self.getLookVector(),
                    self.getUpVector(),
                    self.getLeftVector(),
                    partialTick,
                    snapshot.revision(),
                    snapshot.fallback(),
                    snapshot.tick()
            );
        } catch (RuntimeException failure) {
            try {
                GravityDebugLog.log(
                        entity,
                        "camera-position",
                        "debug log failed: %s",
                        failure.toString()
                );
            } catch (RuntimeException ignored) {
                // Debug instrumentation must never break camera setup.
            }
        }
    }
}
