package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.world.entity.LivingEntity;

/** Read-only jump eligibility at the native gate, before travel can restore ground state.
 * This evidence lives for this query only: it grants neither traction nor support transport. */
public final class GravityJumpSupportQuery {
    private GravityJumpSupportQuery() {}

    /** Packet deltas have no simulation interval. Inspect current geometric
     * contact, without filtering it through the server's predicted velocity.
     * This read-only pre-move query uses the admitted installed body and either
     * the active operation's scene or a standalone capture at this boundary. */
    public static boolean departsStationarySupport(LivingEntity entity,
            cc.sighs.gravityengine.api.math.Vec3d displacement) {
        try {
            var capture = GravityCurrentSupportQuery.capture(entity);
            var context = capture.context();
            var result = FeetSupportQuery.query(capture.body(), capture.frame(), capture.scene(), 0.0D, 0.0D,
                    context.preferredSupportFace(), context);
            return !context.workTracker().limitExceeded() && !context.supportWorkTracker().limitExceeded()
                    && result.departsStationarySupport(displacement);
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return false;
        }
    }

    public static boolean canJump(LivingEntity entity) {
        try {
            var capture = GravityCurrentSupportQuery.capture(entity);
            var context = capture.context();
            var result = FeetSupportQuery.query(capture.body(), capture.frame(), capture.scene(), 0.0D, 0.0D,
                    context.preferredSupportFace(), context);
            if (result.indeterminate() || context.workTracker().limitExceeded()
                    || context.supportWorkTracker().limitExceeded()) return false;
            var velocity = MinecraftMathAdapter.toVec3d(entity.getDeltaMovement());
            for (var candidate : result.candidates()) {
                var contact = candidate.support();
                // A steep finite lower-body contact is enough. Tangential uphill
                // motion is legal; separating from the material surface is not.
                double separatingSpeed = velocity.subtract(contact.surfaceVelocity()).dot(contact.normal());
                if (separatingSpeed <= CollisionTolerances.ZERO_VECTOR_EPSILON) return true;
            }
            return false;
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return false;
        }
    }
}
