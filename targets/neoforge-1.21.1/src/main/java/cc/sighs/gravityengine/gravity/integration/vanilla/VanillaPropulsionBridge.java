package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.player.CharacterControlRuntime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Propulsion spatial semantics: semantic-view push direction and
 * support-relative ground release.
 */
public final class VanillaPropulsionBridge {
    private VanillaPropulsionBridge() {}

    public record Jump(
            cc.sighs.gravityengine.gravity.movement.CharacterLocomotionTechnique technique,
            VanillaActorSnapshot actor,
            Vec3 before,
            Vec3d releaseVelocity
    ) {}

    /** One immutable frame/look/release sample for the accepted jump. */
    public static Jump prepareJump(LivingEntity entity) {
        var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess
                .cast(entity).gravityengine$gravityComponent().operationState();
        if (!GravityInfluencePolicy.usesCustomLocomotion(entity)
                && runtime.persistentSupportState() == null) return null;
        var frame = GravityFrameAccess.authoritativeFrame(entity);
        var technique = CharacterControlRuntime.jumpTechnique(entity, frame);
        var releaseSample = technique == cc.sighs.gravityengine.gravity.movement
                .CharacterLocomotionTechnique.GROUND_AIR
                ? cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                        .captureJumpReleaseVelocity(entity, runtime)
                : java.util.Optional.<Vec3d>empty();
        // Native jump replaces the reference vertical component. Only tangent
        // support momentum survives that replacement and needs deduplication.
        Vec3d credited = runtime.supportVelocityContribution();
        Vec3d release = releaseSample.map(value -> value.subtract(
                credited.subtract(frame.up().multiply(credited.dot(frame.up()))))).orElse(Vec3d.ZERO);
        return new Jump(technique, VanillaActorBridge.capture(entity, frame),
                entity.getDeltaMovement(), release);
    }

    /** Native power replaces reference-vertical speed, preserving tangent velocity. */
    public static Vec3 jumpVelocity(Jump jump, double power) {
        var actor = jump.actor();
        return MinecraftMathAdapter.toMinecraft(
                cc.sighs.gravityengine.gravity.movement.GravityPhysics.jumpFromGround(
                        MinecraftMathAdapter.toVec3d(jump.before()),
                        (float) power,
                        false,
                        MinecraftMathAdapter.toVec3d(actor.viewForward()),
                        MinecraftMathAdapter.toVec3d(actor.zeroPitchHeading()),
                        actor.referenceFrame()
                )
        );
    }

    /** Vanilla owns sprint eligibility and impulse magnitude; semantic look owns heading. */
    public static Vec3 sprintJumpImpulse(Jump jump, Vec3 vanillaImpulse) {
        var actor = jump.actor();
        return MinecraftMathAdapter.toMinecraft(
                cc.sighs.gravityengine.gravity.movement.GravityPhysics.tangentForward(
                        MinecraftMathAdapter.toVec3d(actor.viewForward()),
                        actor.referenceFrame(),
                        MinecraftMathAdapter.toVec3d(actor.zeroPitchHeading())
                )
        ).scale(vanillaImpulse.length());
    }
    /**
     * Riptide-style semantic propulsion direction.  Vanilla's spin strength
     * is caller-owned and scales a normalized view direction.
     */
    public static Vec3 propulsionDirection(
            VanillaActorSnapshot actor,
            double strength
    ) {
        Objects.requireNonNull(actor, "actor");
        if (!Double.isFinite(strength)) {
            throw new IllegalArgumentException(
                    "propulsion strength must be finite");
        }
        return actor.viewForward().scale(strength);
    }

    /**
     * Release movement away from support/reference up.  Vanilla's grounded
     * Riptide release {@code (0, 1.2, 0)} is reference-space vertical, not
     * body or view vertical.
     */
    public static Vec3 groundedReleaseMovement(
            VanillaActorSnapshot actor,
            double magnitude
    ) {
        Objects.requireNonNull(actor, "actor");
        if (!Double.isFinite(magnitude)) {
            throw new IllegalArgumentException(
                    "release magnitude must be finite");
        }
        return actor.referenceUp().scale(magnitude);
    }

    /**
     * 21.1.249 attached-firework hand offset.
     *
     * <p>Vanilla computes a radius-0.5 yaw-only hand-side offset and later
     * adds it to {@code entity.position()}. For an independently tilted
     * custom body, the Vanilla network position anchor is not necessarily
     * the anatomical lower-body anchor. The returned vector therefore
     * contains two terms:</p>
     *
     * <ol>
     *   <li>network position P -> anatomical lower-body anchor;</li>
     *   <li>the Vanilla yaw +/-80 degree, radius-0.5 hand offset transformed
     *       by the physical body orientation.</li>
     * </ol>
     *
     * <p>This is gameplay attachment geometry, not render interpolation.
     * Body orientation, reference orientation and semantic view remain
     * independent.</p>
     */
    public static Vec3 fireworkHandOffset(VanillaActorSnapshot actor,
            net.minecraft.world.entity.HumanoidArm arm, float vanillaYaw) {
        Vec3d localAim =
                actor.attachmentFrame().worldToLocal(
                        MinecraftMathAdapter.toVec3d(
                                actor.viewForward()
                        )
                );
        // Hand attachment is torso-tangent heading, derived from controller evidence.
        float yaw = actor.look().source() == cc.sighs.gravityengine.look.SemanticLookSnapshot.Source.BODY_ATTITUDE
                ? (float)Math.toDegrees(Math.atan2(-localAim.x(), localAim.z())) : vanillaYaw;
        Vec3 local = VanillaProjectileBridge.referenceYawForward(
                cc.sighs.gravityengine.gravity.GravityFrame.DEFAULT,
                yaw + (arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 80.0F : -80.0F)).scale(0.5D);
        var orientation = actor.customBody() ? actor.attachmentFrame() : actor.referenceFrame().orientation();
        Vec3d world =
                orientation.localToWorld(
                        MinecraftMathAdapter.toVec3d(local)
                );
        Vec3 offset =
                MinecraftMathAdapter.toMinecraft(world);
        // tick adds entity.position; translate that carrier to the anatomical
        // lower attachment anchor when independent Qbody separates the two.
        return VanillaProjectileBridge.bodyHeightPoint(actor, 0.0D)
                .subtract(actor.positionAnchor()).add(offset);
    }

    public static Vec3 fireworkHandOffset(net.minecraft.world.entity.LivingEntity entity,
            VanillaActorSnapshot actor, net.minecraft.world.item.Item item) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) return Vec3.ZERO;
        boolean offhand = player.getOffhandItem().is(item) && !player.getMainHandItem().is(item);
        var arm = offhand ? player.getMainArm().getOpposite() : player.getMainArm();
        return fireworkHandOffset(actor, arm, actor.look().requestedLocalLook().yawDegrees());
    }
}
