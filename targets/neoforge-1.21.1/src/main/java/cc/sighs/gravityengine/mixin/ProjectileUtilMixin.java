package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaTargetingBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * 1.21.1 / NeoForge 21.1.249 projectile targeting seams.
 *
 * <p>Gameplay view origin/direction are translated at their exact Vanilla
 * consumers. Projectile entity collision keeps Vanilla's complete
 * {@code ProjectileUtil.getEntityHitResult(Level, Entity, Vec3, Vec3, AABB,
 * Predicate, float)} implementation and changes only its per-candidate
 * narrow-phase clip when the candidate owns an authoritative exact body.</p>
 *
 * <p>This is intentionally narrower than replacing the public selection
 * method: Vanilla and other Mixins retain ownership of candidate enumeration,
 * predicate handling, margin application, nearest-hit ordering and result
 * construction.</p>
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    private static final String LEVEL_ENTITY_HIT_METHOD =
            "getEntityHitResult"
                    + "(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;F)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;";

    /*
     * ---------------------------------------------------------------------
     * Semantic view ray
     * ---------------------------------------------------------------------
     */

    @Inject(
            method = "getHitResultOnViewVector"
                    + "(Lnet/minecraft/world/entity/Entity;"
                    + "Ljava/util/function/Predicate;D)"
                    + "Lnet/minecraft/world/phys/HitResult;",
            at = @At("HEAD")
    )
    private static void gravityengine$captureProjectileActor(
            Entity projectile,
            Predicate<Entity> filter,
            double scale,
            CallbackInfoReturnable<net.minecraft.world.phys.HitResult> ci,
            @Share("gravityengineProjectileActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        actorRef.set(VanillaActorBridge.capture(projectile));
    }

    @WrapOperation(
            method = "getHitResultOnViewVector"
                    + "(Lnet/minecraft/world/entity/Entity;"
                    + "Ljava/util/function/Predicate;D)"
                    + "Lnet/minecraft/world/phys/HitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;"
                            + "getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private static Vec3 gravityengine$coherentViewDirection(
            Entity projectile,
            float partialTick,
            Operation<Vec3> original,
            @Share("gravityengineProjectileActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        return !actor.transformedLook()
                ? original.call(projectile, partialTick)
                : actor.viewForward();
    }

    @WrapOperation(
            method = "getHitResultOnViewVector"
                    + "(Lnet/minecraft/world/entity/Entity;"
                    + "Ljava/util/function/Predicate;D)"
                    + "Lnet/minecraft/world/phys/HitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;"
                            + "getEyePosition()Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1
    )
    private static Vec3 gravityengine$coherentEyeOrigin(
            Entity projectile,
            Operation<Vec3> original,
            @Share("gravityengineProjectileActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        return !actor.customBody()
                ? original.call(projectile)
                : actor.eye();
    }

    /*
     * ---------------------------------------------------------------------
     * Projectile exact-body narrow phase
     * ---------------------------------------------------------------------
     *
     * 21.1.249 Level-based getEntityHitResult(...) does:
     *
     * candidate.getBoundingBox()
     *          .inflate((double) margin)
     *          .clip(start, end)
     *
     * Preserve that whole Vanilla method. Capture the candidate and the exact
     * Vanilla inflation operand, then refine only the clip result.
     */

    @WrapOperation(
            method = LEVEL_ENTITY_HIT_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;"
                            + "getBoundingBox()Lnet/minecraft/world/phys/AABB;"
            ),
            require = 1
    )
    private static AABB gravityengine$captureProjectileCandidate(
            Entity candidate,
            Operation<AABB> original,
            @Share("gravityengineProjectileCandidate")
            LocalRef<Entity> candidateRef
    ) {
        candidateRef.set(candidate);
        return original.call(candidate);
    }

    @WrapOperation(
            method = LEVEL_ENTITY_HIT_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;"
                            + "inflate(D)Lnet/minecraft/world/phys/AABB;"
            ),
            require = 1
    )
    private static AABB gravityengine$captureProjectileInflation(
            AABB box,
            double amount,
            Operation<AABB> original,
            @Share("gravityengineProjectileInflation")
            LocalRef<Double> inflationRef
    ) {
        inflationRef.set(amount);
        return original.call(box, amount);
    }

    @WrapOperation(
            method = LEVEL_ENTITY_HIT_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;"
                            + "clip(Lnet/minecraft/world/phys/Vec3;"
                            + "Lnet/minecraft/world/phys/Vec3;)"
                            + "Ljava/util/Optional;"
            ),
            require = 1
    )
    private static Optional<Vec3> gravityengine$refineProjectileCandidateClip(
            AABB vanillaInflatedBox,
            Vec3 start,
            Vec3 end,
            Operation<Optional<Vec3>> original,
            @Share("gravityengineProjectileCandidate")
            LocalRef<Entity> candidateRef,
            @Share("gravityengineProjectileInflation")
            LocalRef<Double> inflationRef
    ) {
        /*
         * Always execute the original Vanilla narrow phase first.
         *
         * This preserves the original call chain (including other compatible
         * WrapOperation handlers) and gives us the exact Vanilla fallback for
         * ordinary entities or unsupported/non-standard margin values.
         */
        Optional<Vec3> vanillaClip =
                original.call(vanillaInflatedBox, start, end);

        Entity candidate = candidateRef.get();
        Double inflation = inflationRef.get();

        if (candidate == null || inflation == null) {
            return vanillaClip;
        }

        return VanillaTargetingBridge.refineProjectileCandidateClip(
                candidate,
                inflation,
                start,
                end,
                vanillaClip
        );
    }
}
