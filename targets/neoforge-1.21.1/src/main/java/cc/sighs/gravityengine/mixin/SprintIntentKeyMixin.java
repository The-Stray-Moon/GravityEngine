package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the dedicated binding's raw level before Vanilla's Toggle Sprint latch.
 * Vanilla retains key processing, click counts, sprint policy and the original call. */
@Mixin(ToggleKeyMapping.class)
public abstract class SprintIntentKeyMixin {
    @Inject(method = "setDown(Z)V", at = @At("HEAD"), require = 1)
    private void gravityengine$sprintIntent(boolean down, CallbackInfo ci) {
        ClientBodyAttitudeControl.observeSprintBinding((KeyMapping)(Object)this, down);
    }
}
