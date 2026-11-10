package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Observes installed camera state, including the cancellable quaternion installer.
 * Never samples a frame, render pose, input or another camera solution. */
@Mixin(Camera.class)
public abstract class CameraViewDebugMixin {
    @Shadow private Entity entity;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float roll;

    @WrapMethod(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", require = 1)
    private void gravityengine$traceSetup(BlockGetter level, Entity actor, boolean detached,
            boolean reverse, float partialTick, Operation<Void> original) {
        if (PlayerViewDebugLog.shouldLog(actor)) PlayerViewDebugLog.event(actor, "camera-setup-before",
                "detached=%s reverse=%s partialTick=%s", detached, reverse, partialTick);
        try { original.call(level, actor, detached, reverse, partialTick); }
        finally { gravityengine$traceCamera(actor, "camera-setup-after"); }
    }

    @WrapMethod(method = "setRotation(FFF)V", require = 1)
    private void gravityengine$traceRotation(float yaw, float pitch, float roll, Operation<Void> original) {
        if (!PlayerViewDebugLog.shouldLog(entity)) {
            original.call(yaw, pitch, roll);
            return;
        }
        try (var trace = PlayerViewDebugLog.begin(entity, "camera-rotation")) {
            PlayerViewDebugLog.event(entity, "camera-rotation-request",
                    "requestedYaw=%s requestedPitch=%s requestedRoll=%s", yaw, pitch, roll);
            gravityengine$traceCamera(entity, "camera-output-before");
            try { original.call(yaw, pitch, roll); }
            finally { gravityengine$traceCamera(entity, "camera-output-after"); }
        }
    }

    @Unique
    private void gravityengine$traceCamera(Entity actor, String event) {
        if (!PlayerViewDebugLog.shouldLog(actor)) return;
        Camera camera = (Camera) (Object) this;
        PlayerViewDebugLog.event(actor, event,
                "partialTick=%s yaw=%s pitch=%s roll=%s qCamera=%s forward=%s up=%s left=%s position=%s",
                camera.getPartialTickTime(), yRot, xRot, roll, camera.rotation(),
                camera.getLookVector(), camera.getUpVector(), camera.getLeftVector(), camera.getPosition());

    }
}
