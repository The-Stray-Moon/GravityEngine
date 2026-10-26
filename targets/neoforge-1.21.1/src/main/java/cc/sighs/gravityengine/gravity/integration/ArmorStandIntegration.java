package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

public final class ArmorStandIntegration {
    private ArmorStandIntegration() {}

    /** Committed ownership and current native exclusions, never a desired assignment alone. */
    public static boolean ownsMotion(ArmorStand stand) {
        return !stand.isRemoved() && !stand.isMarker()
                && MovementModeIntegration.desired(stand)==MovementMode.GROUND_AIR
                && GravityInfluencePolicy.committedPlan(stand).kind()==GravityApplicationPlan.Kind.CHARACTER;
    }

    /** Keep vanilla's load-derived bit separate from the externally writable public noPhysics bit.
     * GE never clears the latter, even if an external write equals an old native-derived value. */
    public static boolean nativeNoPhysics(Entity entity, boolean explicit) {
        return explicit || entity instanceof ArmorStand stand
                && ((GravityArmorStandAccess)stand).gravityengine$nativeNoPhysics() && !ownsMotion(stand);
    }
}
