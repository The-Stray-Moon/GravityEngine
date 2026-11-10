package cc.sighs.gravityengine.gravity.minecraft.access;

import cc.sighs.gravityengine.gravity.movement.CharacterControlStep;

public interface CharacterControlAccess {
    CharacterControlStep gravityengine$characterControl();
    cc.sighs.gravityengine.gravity.movement.CharacterControlMode gravityengine$characterMode();

    /** Physical-client input handoff, implemented only by the owning player.
     * Common travel supplies its resolved plan; no client class is loaded here. */
    interface LocalInput {
        boolean gravityengine$descendHeld();
        net.minecraft.world.phys.Vec3 gravityengine$resolveSneakInput(
                cc.sighs.gravityengine.gravity.movement.CharacterControlPlan plan,
                net.minecraft.world.phys.Vec3 travel);
    }
}