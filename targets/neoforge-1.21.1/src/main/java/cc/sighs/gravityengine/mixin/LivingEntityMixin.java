package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.integration.MovementProvenanceIntegration;
import cc.sighs.gravityengine.gravity.look.GravityBodyTurnMath;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.look.PlayerLookIntegration;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements GravityLivingAccess {
    /** 1.21.1 / 21.1.249: the sole aiStep setDeltaMovement(DDD) commits
     * small-component cleanup, after interpolation damping and before AI/travel.
     * Keep this producer distinct from all other velocity writes. */
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"),
            require = 1, allow = 1)
    private void gravityengine$referenceSmallVelocityCleanup(LivingEntity entity,
            double x, double y, double z, Operation<Void> original) {
        Vec3 cleaned = cc.sighs.gravityengine.gravity.integration.vanilla.VanillaLivingVelocity
                .smallVelocityCleanupInReferenceSpace(entity, new Vec3(x, y, z));
        original.call(entity, cleaned.x, cleaned.y, cleaned.z);
    }

    /** The knockback producer owns its accepted velocity change, including event/resistance limits. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(
            method = "knockback"
    )
    private void gravityengine$captureKnockback(
            double strength,
            double x,
            double z,
            Operation<Void> original
    ) {
        LivingEntity entity =
                (LivingEntity) (Object) this;

        Vec3 before =
                entity.getDeltaMovement();

        original.call(strength, x, z);

        if (!cc.sighs.gravityengine.gravity.policy
                .GravityInfluencePolicy
                .usesCustomLocomotion(entity)) {
            return;
        }

        /*
         * This producer is intrinsically an external impulse. The shared
         * integration records only the accepted delta and only for entities
         * whose movement evidence is GravityEngine-owned.
         */
        MovementProvenanceIntegration.recordAcceptedExternalPush(
                entity,
                before,
                entity.getDeltaMovement()
        );
    }
    @org.spongepowered.asm.mixin.gen.Accessor("jumping")
    public abstract boolean gravityengine$isJumping();
    @org.spongepowered.asm.mixin.gen.Accessor("jumping")
    public abstract void gravityengine$setJumping(boolean jumping);
    @org.spongepowered.asm.mixin.gen.Accessor("noJumpDelay")
    public abstract int gravityengine$getJumpDelay();
    @org.spongepowered.asm.mixin.gen.Accessor("noJumpDelay")
    public abstract void gravityengine$setJumpDelay(int ticks);
    @Shadow protected abstract float getJumpPower();
    @Shadow protected abstract float getFrictionInfluencedSpeed(float friction);
    @Shadow public abstract float getFlyingSpeed();
    @Shadow public abstract boolean shouldDiscardFriction();
    @Shadow protected abstract float getMaxHeadRotationRelativeToBody();

    /*
     * Exact 21.1.249 seam: LivingEntity.tick computes a world-XZ walk heading
     * and then invokes tickHeadTurn(float targetBodyYaw, float movementAmount).
     * The replacement is gated by semantic look authority, not by the gravity
     * presentation plan: BodyAttitude look can be ACTIVE at default gravity
     * (strength well below one g) while custom presentation is false, and that
     * look still owns yBodyRot heading semantics.  Entities without GravityEngine
     * look authority receive the original Vanilla arguments untouched.
     *
     * <p>REFERENCE_ALIGNED with a custom reference keeps Vanilla-compatible
     * yBodyRot as real heading state. FREE_ATTITUDE rendering may ignore it
     * as a root orientation, while tickHeadTurn still maintains the body-heading
     * Vanilla API carrier. Neither path rotates the character collider.</p>
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "tickHeadTurn(FF)F"
            ),
            require = 1
    )
    private float gravityengine$gravityLocalBodyTurn(
            LivingEntity instance,
            float targetBodyYaw,
            float movementAmount,
            Operation<Float> original
    ) {
        // ArmorStand.tickHeadTurn locks its authored yaw and returns no walk animation.
        // Passive displacement must not turn the decoration as if it were walking.
        if (instance instanceof net.minecraft.world.entity.decoration.ArmorStand
                || !PlayerLookIntegration.usesGravityEngineLook(instance)) {
            return original.call(instance, targetBodyYaw, movementAmount);
        }
        /*
         * One integration boundary resolves the frame once; the semantic view
         * is then resolved against that same frame (the frame-aware facade
         * never re-resolves the entity's authoritative frame).
         */
        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(instance);
        SemanticLookSnapshot look =
                PlayerLookIntegration.capture(instance, frame);
        Vec3 semanticViewForward =
                cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter
                        .toMinecraft(look.forward());
        GravityBodyTurnMath.BodyTurnInput corrected =
                GravityBodyTurnMath.resolve(
                        instance,
                        frame,
                        semanticViewForward,
                        MovementProvenanceIntegration.bodyHeadingDisplacement(
                                instance));
        float semanticViewYaw = GravityBodyTurnMath.semanticViewYaw(
                frame, semanticViewForward, instance.yBodyRot);
        GravityBodyTurnMath.BodyTurnStep step =
                GravityBodyTurnMath.tickHeadTurn(
                instance.yBodyRot,
                corrected.targetBodyYaw(),
                semanticViewYaw,
                this.getMaxHeadRotationRelativeToBody(),
                corrected.movementAmount());
        instance.yBodyRot = step.bodyYaw();
        return step.animationStep();
    }

    @Override public float gravityengine$getJumpPower() { return this.getJumpPower(); }
    @Override public float gravityengine$getFrictionInfluencedSpeed(float friction) { return this.getFrictionInfluencedSpeed(friction); }
    @Override public float gravityengine$getFlyingSpeed() { return this.getFlyingSpeed(); }
    @Override public boolean gravityengine$shouldDiscardFriction() { return this.shouldDiscardFriction(); }
    @Override public float gravityengine$getMaxHeadRotationRelativeToBody() {
        return this.getMaxHeadRotationRelativeToBody();
    }
}
