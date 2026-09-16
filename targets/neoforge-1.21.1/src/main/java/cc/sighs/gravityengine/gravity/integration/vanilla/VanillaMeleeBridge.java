package cc.sighs.gravityengine.gravity.integration.vanilla;

import net.neoforged.neoforge.common.CommonHooks;
import net.minecraft.world.entity.LivingEntity;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.integration.MovementProvenanceIntegration;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;

/**
 * Close-combat response bridge: reference-tangent melee heading, attack sweep
 * eligibility and translated knockback.
 *
 * <p>The heading, sweep and knockback operands all belong to the attacker's
 * and target's reference frames, so one owner keeps the translation policy
 * coherent.</p>
 */
public final class VanillaMeleeBridge {
    private VanillaMeleeBridge() {}

    /** Reference-tangent projection tolerance for melee heading operands. */
    private static final double TANGENT_EPSILON = 1.0E-12D;

    /**
     * Deterministic melee/combat heading in the actor's reference tangent
     * plane.  Semantic view is projected first; a view-pole fallback uses the
     * zero-pitch semantic heading projected into the same plane, then the
     * frame's canonical tangent forward.
     */
    public static Vec3 meleeWorldDirection(
            VanillaActorSnapshot actor
    ) {
        Objects.requireNonNull(actor, "actor");
        Vec3 up = actor.referenceUp();
        Vec3 heading = AttitudeSpaceTransform.projectedUnit(
                actor.viewForward(), up, TANGENT_EPSILON);
        if (heading == null) {
            heading = AttitudeSpaceTransform.projectedUnit(
                    actor.zeroPitchHeading(), up, TANGENT_EPSILON);
        }
        if (heading == null) {
            heading = actor.referenceFrame().forward();
        }
        return heading;
    }

    /**
     * Focused knockback integration for the narrow {@code Player.attack}
     * producer seam.
     *
     * <p>Vanilla {@code LivingEntity.knockback(strength, x, z)} can only
     * carry a world-XZ direction.  This method reproduces the same
     * NeoForge event/resistance ordering and the same velocity transform in
     * the target reference frame, then applies the full reference-tangent
     * world direction (which may legitimately contain a world-Y component
     * under wall/oblique gravity).  It deliberately does not globally
     * reinterpret every {@code knockback} call.</p>
     *
     * <p>The target reference is captured once, including when only the
     * target needs translation.</p>
     */
    public static boolean tryPlayerKnockback(LivingEntity target, VanillaActorSnapshot attacker,
            double strength, double vanillaX, double vanillaZ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attacker, "attacker");
        if (!Double.isFinite(strength) || strength < 0.0D) {
            throw new IllegalArgumentException(
                    "knockback strength must be finite and non-negative");
        }
        GravityFrame targetFrame = GravityFrameAccess.authoritativeFrame(target);
        boolean translatedHeading =
                attacker.transformedLook()
                        || attacker.nonDefaultReferenceFrame();
        if (!translatedHeading && targetFrame.isDefault()) return false;
        Vec3 carrier = translatedHeading
                ? meleeEventCarrier(attacker) : new Vec3(vanillaX, 0, vanillaZ);
        applyPlayerKnockback(target, attacker, targetFrame, strength, carrier);
        return true;
    }

    /**
     * NeoForge knockback-event Vanilla API carrier.
     *
     * <p>Under default gravity the values retain Vanilla world-XZ meaning.
     * When GravityEngine translates an arbitrary-gravity player attack, the
     * event's ratioX/ratioZ pair is intentionally used as a two-component
     * carrier in the attacker's reference left/forward plane so an
     * event-modified direction can still be reconstructed as a full
     * world-space tangent impulse.</p>
     *
     * <p>This preserves event cancellation, strength mutation and directional
     * mutation for the translated operation, but third-party code that assumes
     * ratioX/ratioZ are always literal world X/Z is crossing a representation
     * boundary under custom gravity. Do not describe this as transparent
     * world-XZ API equivalence.</p>
     */
    private static Vec3 meleeEventCarrier(
            VanillaActorSnapshot attacker
    ) {
        return attacker.referenceFrame()
                .worldToLocal(
                        meleeWorldDirection(attacker)
                                .reverse()
                );
    }

    private static void applyPlayerKnockback(LivingEntity target, VanillaActorSnapshot attacker,
            GravityFrame targetFrame, double strength, Vec3 carrier) {
        // 21.1.249: event once before resistance, impulse flag, random degeneracy
        // repair and velocity read/commit. The original knockback call is suppressed.
        var event = CommonHooks.onLivingKnockBack(target, (float) strength, carrier.x, carrier.z);
        if (event.isCanceled()) return;
        strength = event.getStrength();
        double eventX = event.getRatioX();
        double eventZ = event.getRatioZ();
        strength *= 1.0D - target.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes
                        .KNOCKBACK_RESISTANCE);
        if (!(strength > 0.0D)) {
            return;
        }
        target.hasImpulse = true;

        // Preserve Vanilla's exact small-carrier randomization policy.
        while (eventX * eventX + eventZ * eventZ < (double) 1.0E-5F) {
            eventX = (Math.random() - Math.random()) * 0.01D;
            eventZ = (Math.random() - Math.random()) * 0.01D;
        }
        Vec3 before = target.getDeltaMovement();
        Vec3 pushDirection = attacker.referenceFrame().localToWorld(
                new Vec3(eventX, 0, eventZ).normalize()).reverse();
        GravityFrame frame = targetFrame;
        Vec3 up = frame.up();
        double upBefore = before.dot(up);
        Vec3 tangentBefore = before.subtract(
                up.scale(upBefore));
        double upAfter = target.onGround()
                ? Math.min(0.4D, upBefore * 0.5D + strength)
                : upBefore;
        Vec3 after = tangentBefore.scale(0.5D)
                .add(up.scale(upAfter))
                .add(pushDirection.scale(strength));
        target.setDeltaMovement(after);
        MovementProvenanceIntegration.recordAcceptedExternalPush(
                target,
                before,
                after
        );
    }

    /**
     * Exact-body refinement for Vanilla's sweep broad-phase volume.
     *
     * <p>The target's semantic look is deliberately not captured: sweep
     * occupancy depends only on target geometry.</p>
     */
    private static boolean exactBodyOverlapsAabb(
            LivingEntity target,
            AABB broadVolume
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(broadVolume, "broadVolume");

        var exactBody =
                VanillaActorBridge
                        .exactBodyForQuery(target);

        if (exactBody == null) {
            return true;
        }

        return VanillaBodySensors.intersects(exactBody, broadVolume);
    }

    /**
     * Sweep candidate eligibility used before any Vanilla per-target side
     * effect.  Ordinary Vanilla entities keep their enclosing-AABB
     * semantics; a custom exact body must genuinely overlap the sweep volume.
     */
    public static boolean sweepCandidatePasses(
            LivingEntity target,
            AABB sweepBox
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sweepBox, "sweepBox");

        if (!cc.sighs.gravityengine.gravity.policy
                .GravityInfluencePolicy
                .usesCustomBody(target)) {
            return true;
        }
        return exactBodyOverlapsAabb(target, sweepBox);
    }
}
