package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
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
        if (!GravityInfluencePolicy.usesCustomLocomotion(entity)) {
            return vanillaCleaned;
        }

        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(entity);

        if (!GravityInfluencePolicy.requiresReferenceGeometry(frame)) {
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

        RestingContactSnapshot support =
                stableStaticSupport(entity);

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
        if (!GravityInfluencePolicy.requiresReferenceGeometry(frame)) {
            return vanillaCleaned;
        }

        Vec3 local =
                frame.worldToLocal(worldVelocity);

        Vec3 cleanedLocal =
                new Vec3(
                        cleanComponent(local.x),
                        cleanComponent(local.y),
                        cleanComponent(local.z)
                );

        return frame.localToWorld(cleanedLocal);
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
                support.normal();

        Vec3 surfaceVelocity =
                support.surfaceVelocity();

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

    /**
     * Returns only support evidence that is safe to consume before the next
     * movement operation has revalidated the world.
     *
     * <p>The aiStep cleanup runs before travel opens the next collision scene,
     * so it must not treat an old moving-body publication as current. Restrict
     * this bridge to the already-supported cross-tick static block-face
     * authority.</p>
     */
    private static RestingContactSnapshot stableStaticSupport(
            LivingEntity entity
    ) {
        RestingContactSnapshot support =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime()
                        .restingContactSnapshot();

        if (support == null) {
            return null;
        }

        long gameTick =
                entity.level().getGameTime();

        if (!support.usableAtStepStart(gameTick)) {
            return null;
        }

        if (!support.staticSupport()) {
            return null;
        }

        /*
         * aiStep executes before the new operation can validate dynamic or
         * approximate support. Only a finite trusted static block face has a
         * cross-tick plane that is authoritative here.
         */
        if (!support.planePreservationEligible()) {
            return null;
        }

        return support;
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
