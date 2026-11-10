package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.movement.CharacterControlPlan;
import net.minecraft.client.player.Input;
import net.minecraft.world.phys.Vec3;

/** One keyboard snapshot and its provisional Vanilla slowdown. Travel publishes
 * the final interpretation; no environmental evidence or toggle is resolved here. */
public final class CharacterSneakInput {
    private boolean held;
    private float appliedScale = 1;
    private float sneakSpeed = 1;
    private boolean captured;
    private boolean previousDescendOwner;
    private boolean resolved;

    /** Called after the native input poll, before Vanilla input conditioning.
     * The caller polls without slowdown so even a zero sneak-speed attribute
     * cannot destroy the raw movement axes needed by an entering swim step. */
    public void capture(Input input, boolean movingSlowly, float speed, boolean previousDescendOwner) {
        this.previousDescendOwner = previousDescendOwner;
        captured = true;
        resolved = false;
        held = input.shiftKeyDown;
        sneakSpeed = speed;
        appliedScale = movingSlowly && speed != 0 ? speed : 1;
        input.leftImpulse *= appliedScale;
        input.forwardImpulse *= appliedScale;
    }

    public boolean held() { return captured && held; }
    public boolean changesOwnership(CharacterControlPlan plan) {
        return captured && previousDescendOwner != plan.ownsDescendInput();
    }

    public Vec3 resolve(Input input, CharacterControlPlan plan, boolean movingSlowly, Vec3 travel) {
        if (!captured || resolved) return travel;
        resolved = true;
        input.shiftKeyDown = held && !plan.ownsDescendInput();
        float wantedScale = movingSlowly ? sneakSpeed : 1;
        double ratio = wantedScale / (double) appliedScale;
        input.leftImpulse = (float) (input.leftImpulse * ratio);
        input.forwardImpulse = (float) (input.forwardImpulse * ratio);
        appliedScale = wantedScale == 0 ? 1 : wantedScale;
        // Keep item-use conditioning and LivingEntity's 0.98 input damping.
        return ratio == 1 ? travel : new Vec3(travel.x * ratio, travel.y, travel.z * ratio);
    }

    public void clear() {
        captured = resolved = held = previousDescendOwner = false;
        appliedScale = sneakSpeed = 1;
    }
}
