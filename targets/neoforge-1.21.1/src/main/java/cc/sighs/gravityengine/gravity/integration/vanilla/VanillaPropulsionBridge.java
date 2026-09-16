package cc.sighs.gravityengine.gravity.integration.vanilla;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.player.CharacterControlRuntime;

import java.util.Objects;

/**
 * Propulsion spatial semantics: semantic-view push direction and
 * support-relative ground release.
 */
public final class VanillaPropulsionBridge {
    private VanillaPropulsionBridge() {}

    public record Jump(cc.sighs.gravityengine.gravity.movement.CharacterLocomotionTechnique technique,
            VanillaActorSnapshot actor, Vec3 before) {}

    /** One frame/look sample for both native jump velocity writes. */
    public static Jump prepareJump(LivingEntity entity) {
        if (!GravityInfluencePolicy.usesCustomLocomotion(entity)) return null;
        var frame = GravityFrameAccess.authoritativeFrame(entity);
        return new Jump(CharacterControlRuntime.jumpTechnique(entity, frame),
                VanillaActorBridge.capture(entity, frame),
                entity.getDeltaMovement());
    }

    /** Native power replaces reference-vertical speed, preserving tangent velocity. */
    public static Vec3 jumpVelocity(Jump jump, double power) {
        var actor = jump.actor();
        return cc.sighs.gravityengine.gravity.movement.GravityPhysics.jumpFromGround(jump.before(),
                (float) power, false, actor.viewForward(), actor.zeroPitchHeading(), actor.referenceFrame());
    }

    /** Vanilla owns sprint eligibility and impulse magnitude; semantic look owns heading. */
    public static Vec3 sprintJumpImpulse(Jump jump, Vec3 vanillaImpulse) {
        var actor = jump.actor();
        return cc.sighs.gravityengine.gravity.movement.GravityPhysics.tangentForward(
                actor.viewForward(), actor.referenceFrame(), actor.zeroPitchHeading()).scale(vanillaImpulse.length());
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
        var localAim = actor.attachmentFrame().worldToLocal(new org.joml.Vector3d(
                actor.viewForward().x, actor.viewForward().y, actor.viewForward().z), new org.joml.Vector3d());
        // Hand attachment is torso-tangent heading, derived from controller evidence.
        float yaw = actor.look().source() == cc.sighs.gravityengine.look.SemanticLookSnapshot.Source.BODY_ATTITUDE
                ? (float)Math.toDegrees(Math.atan2(-localAim.x, localAim.z)) : vanillaYaw;
        Vec3 local = VanillaProjectileBridge.referenceYawForward(
                cc.sighs.gravityengine.gravity.GravityFrame.DEFAULT,
                yaw + (arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 80.0F : -80.0F)).scale(0.5D);
        var orientation = actor.customBody() ? actor.attachmentFrame() : actor.referenceFrame().orientation();
        org.joml.Vector3d world = orientation.localToWorld(
                new org.joml.Vector3d(local.x, local.y, local.z), new org.joml.Vector3d());
        Vec3 offset = new Vec3(world.x, world.y, world.z);
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
