package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
        try {
            original.call(
                    level, entity, detached, thirdPersonReverse, partialTick);
        } finally {
            this.gravityengine$insideSetup = false;
            this.gravityengine$setupRotationIndex = 0;
        }
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
        rotation.set(
                (float) state.rotation().x(),
                (float) state.rotation().y(),
                (float) state.rotation().z(),
                (float) state.rotation().w()
        );
        Vector3f forwards = ((Camera) (Object) this).getLookVector();
        forwards.set(
                (float) state.forwards().x(),
                (float) state.forwards().y(),
                (float) state.forwards().z()
        );
        Vector3f up = ((Camera) (Object) this).getUpVector();
        up.set(
                (float) state.up().x(),
                (float) state.up().y(),
                (float) state.up().z()
        );
        Vector3f left = ((Camera) (Object) this).getLeftVector();
        left.set(
                (float) state.left().x(),
                (float) state.left().y(),
                (float) state.left().z()
        );
        this.xRot = state.xRot();
        this.yRot = state.yRot();
        this.roll = state.roll();
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
    }
}
