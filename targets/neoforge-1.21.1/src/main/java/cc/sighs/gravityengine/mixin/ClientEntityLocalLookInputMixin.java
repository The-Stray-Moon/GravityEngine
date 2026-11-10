package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Production pre-clamp input forwarding. Always applied on the client. */
@Mixin(Entity.class)
public abstract class ClientEntityLocalLookInputMixin {
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

}
