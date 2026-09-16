package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Debug-only attribution for writes to the local gravity-relative look scalars. */
@Mixin(Entity.class)
public abstract class ClientEntityLocalLookTraceMixin {
    /**
     * Raw look-input capture at the lowest stable pre-clamp seam.
     *
     * <p>MouseHandler feeds {@code player.turn(yawDelta, pitchDelta)} and
     * Vanilla Entity.turn multiplies by 0.15 and clamps pitch afterwards.
     * Capturing the pre-clamp scalar deltas here gives the attitude input
     * layer exactly what Vanilla was about to add, so saturated pitch keeps
     * flowing to whole-body control while the {@code xRot} display endpoint
     * stays clamped.  The accumulator is drained exactly once per player tick
     * by {@link ClientBodyAttitudeControl#beginTick}.</p>
     */
    @Inject(method = "turn", at = @At("HEAD"), require = 1)
    private void gravityengine$captureRawLookInput(
            double yawDelta,
            double pitchDelta,
            CallbackInfo ci
    ) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof LocalPlayer player)
                || !Double.isFinite(yawDelta)
                || !Double.isFinite(pitchDelta)) {
            return;
        }
        ClientBodyAttitudeControl.accumulateRawLook(
                player,
                (float) (yawDelta * 0.15D),
                (float) (pitchDelta * 0.15D)
        );
    }

    @Inject(method = "setYRot", at = @At("HEAD"), require = 1)
    private void gravityengine$traceLocalYawWrite(float value, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        Entity self = (Entity) (Object) this;
        if (player != self
                || !GravityDebugLog.ENABLED
                || !GravityInfluencePolicy.usesCustomBody(self)
                || Float.floatToIntBits(self.getYRot()) == Float.floatToIntBits(value)) return;
        GravityDebugLog.log(
                self,
                "client-local-yaw-write",
                "before=%.3f after=%.3f source=%s",
                self.getYRot(), value, callerContext()
        );
    }

    @Inject(method = "setXRot", at = @At("HEAD"), require = 1)
    private void gravityengine$traceLocalPitchWrite(float value, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        Entity self = (Entity) (Object) this;
        if (player != self
                || !GravityDebugLog.ENABLED
                || !GravityInfluencePolicy.usesCustomBody(self)
                || Float.floatToIntBits(self.getXRot()) == Float.floatToIntBits(value)) return;
        GravityDebugLog.log(
                self,
                "client-local-pitch-write",
                "before=%.3f after=%.3f source=%s",
                self.getXRot(), value, callerContext()
        );
    }

    private static String callerContext() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder result = new StringBuilder();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(ClientEntityLocalLookTraceMixin.class.getName())
                    || className.startsWith("org.spongepowered.asm.mixin")) continue;
            if (!result.isEmpty()) result.append(" <- ");
            result.append(className).append('#').append(element.getMethodName())
                    .append(':').append(element.getLineNumber());
            if (result.toString().chars().filter(ch -> ch == '<').count() >= 3) break;
            if (result.length() >= 320) break;
        }
        return result.toString();
    }
}
