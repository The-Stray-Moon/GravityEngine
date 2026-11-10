package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Operand translation for Vanilla LivingEntity velocity policies.
 *
 * <p>Persistent {@code deltaMovement} and its Vanilla write boundary remain
 * world-space. Only the temporary operands used by Vanilla's small-component
 * cleanup are translated.</p>
 */
public final class VanillaLivingVelocity {
    private VanillaLivingVelocity() {}

    /**
     * Vanilla 1.21.1 {@code LivingEntity.aiStep} removes velocity components
     * whose absolute value is strictly below {@link LivingEntity#MIN_MOVEMENT_DISTANCE}.
     *
     * <p>Under arbitrary gravity, applying that rule directly to world XYZ can
     * turn pure reference-vertical motion into tangent momentum, so detached
     * custom locomotion performs the same component rule in the authoritative
     * reference frame.</p>
     *
     * <p>Stable support introduces one additional constraint: the cleanup is a
     * numerical deadzone, not a collision response. It therefore must not
     * change the actor's already-valid velocity component normal to the real
     * support plane. Otherwise clearing a tiny reference-frame tangent
     * component can rotate the remaining vector away from the support plane,
     * producing a small artificial detach/penetration velocity every tick.</p>
     */
    public static Vec3 smallVelocityCleanupInReferenceSpace(
            LivingEntity entity,
            Vec3 vanillaCleaned
    ) {
        boolean customLocomotion=GravityInfluencePolicy.usesCustomLocomotion(entity);
        var runtime=cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();
        var credit=runtime.supportVelocityContribution();
        if (!customLocomotion && (credit.lengthSquared()==0
                || !GravityInfluencePolicy.hasExternalCollisionProviders(entity))) {
            return vanillaCleaned;
        }

        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(entity);

        // World deltaMovement includes the previously committed support credit.
        // Erasing one small world component while retaining that credit creates
        // opposite relative momentum on the next travel. Clean only relative
        // velocity when current geometry still verifies the supporting face.
        if(credit.lengthSquared()!=0) {
            Vec3 before=entity.getDeltaMovement();
            Vec3 relative=before.subtract(MinecraftMathAdapter.toMinecraft(credit));
            Vec3 relativeCleaned=smallVelocityCleanupInReferenceSpace(relative,new Vec3(
                    cleanComponent(relative.x),cleanComponent(relative.y),cleanComponent(relative.z)),frame);
            Vec3 proposed=relativeCleaned.add(MinecraftMathAdapter.toMinecraft(credit));
            if(proposed.equals(before)) return before;
            var currentSupport=GravityCurrentSupportQuery.stableSupport(entity).orElse(null);
            if(currentSupport!=null && currentSupport.faceIdentity()!=null
                    && currentSupport.faceIdentity().dynamicSupport())
                return preserveSupportNormalVelocity(before,proposed,currentSupport);
            // A missing stable-support proof also includes legitimate outward
            // velocity and carrier deceleration. The travel owner has not yet
            // released this momentum's provenance. World-component cleanup
            // here would erase momentum while retaining its credit, creating
            // opposite relative velocity. Defer numerical cleanup; do not
            // invent support or revoke credit outside its movement owner.
            return before;
        }
        if(!customLocomotion) return vanillaCleaned;

        if (!BodyRepresentation.requiresReferenceGeometry(frame)) {
            /*
             * Default-reference parity: preserve Vanilla's exact result and
             * avoid a transform round trip.
             */
            return vanillaCleaned;
        }

        Vec3 worldVelocity =
                entity.getDeltaMovement();

        Vec3 referenceCleaned =
                smallVelocityCleanupInReferenceSpace(
                        worldVelocity,
                        vanillaCleaned,
                        frame
                );

        if (referenceCleaned.equals(worldVelocity)) return referenceCleaned;
        RestingContactSnapshot support = GravityCurrentSupportQuery.stableSupport(entity).orElse(null);

        if (support == null) {
            return referenceCleaned;
        }

        return preserveSupportNormalVelocity(
                worldVelocity,
                referenceCleaned,
                support
        );
    }

    /**
     * Pure reference-frame implementation used by detached behavior and
     * focused tests.
     */
    public static Vec3 smallVelocityCleanupInReferenceSpace(
            Vec3 worldVelocity,
            Vec3 vanillaCleaned,
            GravityFrame frame
    ) {
        if (!BodyRepresentation.requiresReferenceGeometry(frame)) {
            return vanillaCleaned;
        }

        Vec3d local =
                frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(
                                worldVelocity
                        )
                );

        Vec3d cleanedLocal =
                new Vec3d(
                        cleanComponent(local.x()),
                        cleanComponent(local.y()),
                        cleanComponent(local.z())
                );

        return MinecraftMathAdapter.toMinecraft(
                frame.localToWorld(cleanedLocal)
        );
    }

    /**
     * Preserve the support-relative normal velocity that existed before the
     * numerical cleanup.
     *
     * <p>This is deliberately not "set normal velocity to zero". Existing
     * legal outward motion, moving-surface-relative motion, knockback and other
     * actor velocity remain intact. The only forbidden effect is for the
     * deadzone itself to change the support-normal scalar.</p>
     *
     * <pre>
     * beforeRelativeNormal
     *     = dot(before - surfaceVelocity, normal)
     *
     * afterRelativeNormal
     *     = dot(cleaned - surfaceVelocity, normal)
     *
     * corrected
     *     = cleaned
     *       + normal * (beforeRelativeNormal - afterRelativeNormal)
     * </pre>
     */
    static Vec3 preserveSupportNormalVelocity(
            Vec3 before,
            Vec3 cleaned,
            RestingContactSnapshot support
    ) {
        Vec3 normal =
                MinecraftMathAdapter.toMinecraft(
                        support.normal()
                );

        Vec3 surfaceVelocity =
                MinecraftMathAdapter.toMinecraft(
                        support.surfaceVelocity()
                );

        double beforeNormal =
                before.subtract(surfaceVelocity)
                        .dot(normal);

        double afterNormal =
                cleaned.subtract(surfaceVelocity)
                        .dot(normal);

        double correction =
                beforeNormal - afterNormal;

        if (correction == 0.0D) {
            return cleaned;
        }

        return cleaned.add(
                normal.scale(correction)
        );
    }

    private static double cleanComponent(
            double component
    ) {
        /*
         * Minecraft 1.21.1 aiStep uses strict '<'; a value exactly at the
         * threshold survives.
         */
        return Math.abs(component)
                < LivingEntity.MIN_MOVEMENT_DISTANCE
                ? 0.0D
                : component;
    }
}
