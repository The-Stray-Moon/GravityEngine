package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional gravity-domain local scalar attribution; no input ownership. */
@Mixin(Entity.class)
public abstract class ClientEntityLocalLookDebugMixin {
    @Inject(method = "setYRot", at = @At("HEAD"), require = 1)
    private void gravityengine$traceLocalYawWrite(float value, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        Entity self = (Entity) (Object) this;
        if (player != self
                || !GravityDebugLog.shouldLog(self)
                || !GravityInfluencePolicy.usesCustomBody(self)
                || Float.floatToIntBits(self.getYRot()) == Float.floatToIntBits(value)) return;
        GravityDebugLog.log(
                self,
                "client-local-yaw-write",
                "before=%.3f after=%.3f source=%s",
                self.getYRot(), value, GravityDebugLog.callerStack()
        );
    }

    @Inject(method = "setXRot", at = @At("HEAD"), require = 1)
    private void gravityengine$traceLocalPitchWrite(float value, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        Entity self = (Entity) (Object) this;
        if (player != self
                || !GravityDebugLog.shouldLog(self)
                || !GravityInfluencePolicy.usesCustomBody(self)
                || Float.floatToIntBits(self.getXRot()) == Float.floatToIntBits(value)) return;
        GravityDebugLog.log(
                self,
                "client-local-pitch-write",
                "before=%.3f after=%.3f source=%s",
                self.getXRot(), value, GravityDebugLog.callerStack()
        );
    }

}
