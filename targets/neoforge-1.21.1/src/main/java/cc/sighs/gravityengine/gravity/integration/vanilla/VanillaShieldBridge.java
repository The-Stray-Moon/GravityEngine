package cc.sighs.gravityengine.gravity.integration.vanilla;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;

/**
 * Shield-facing bridge: the defender's reference-tangent facing heading and
 * the incoming source direction projected into the same plane.
 */
public final class VanillaShieldBridge {
    private VanillaShieldBridge() {}

    /** Reference-tangent projection tolerance for shield facing operands. */
    private static final double TANGENT_EPSILON = 1.0E-12D;

    /**
     * Defender shield-facing heading (pitch removed, reference-tangent
     * projected).  This is the directional operand Vanilla would otherwise
     * derive from {@code calculateViewVector(0, yHeadRot)} in world XZ.
     */
    public static Vec3 shieldFacingDirection(
            VanillaActorSnapshot defender
    ) {
        Objects.requireNonNull(defender, "defender");
        Vec3 up = defender.referenceUp();
        Vec3 heading = AttitudeSpaceTransform.projectedUnit(
                defender.zeroPitchHeading(), up, TANGENT_EPSILON);
        if (heading == null) {
            heading = VanillaMeleeBridge.meleeWorldDirection(defender);
        }
        return heading;
    }

    /**
     * Source-to-defender direction projected into the defender's reference
     * tangent plane.  Returns {@code null} when the source is nearly parallel
     * to reference up/down, which cannot be a shield-facing hit in the
     * tangent plane.
     */
    public static Vec3 shieldIncomingDirection(
            VanillaActorSnapshot defender,
            Vec3 sourcePosition
    ) {
        Objects.requireNonNull(defender, "defender");
        Objects.requireNonNull(sourcePosition, "sourcePosition");
        Vec3 displacement = defender.feet().subtract(sourcePosition);
        return AttitudeSpaceTransform.projectedUnit(
                displacement, defender.referenceUp(), TANGENT_EPSILON);
    }
}
