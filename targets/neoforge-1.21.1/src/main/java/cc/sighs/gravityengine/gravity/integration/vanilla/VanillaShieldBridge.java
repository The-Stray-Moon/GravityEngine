package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

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
        var up = MinecraftMathAdapter.toVec3d(
                defender.referenceUp()
        );
        var heading = AttitudeSpaceTransform.projectedUnit(
                MinecraftMathAdapter.toVec3d(
                        defender.zeroPitchHeading()
                ),
                up,
                TANGENT_EPSILON
        );
        if (heading == null) {
            heading = MinecraftMathAdapter.toVec3d(
                    VanillaMeleeBridge.meleeWorldDirection(defender)
            );
        }
        return MinecraftMathAdapter.toMinecraft(heading);
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
        var projected = AttitudeSpaceTransform.projectedUnit(
                MinecraftMathAdapter.toVec3d(displacement),
                MinecraftMathAdapter.toVec3d(
                        defender.referenceUp()
                ),
                TANGENT_EPSILON
        );
        return projected == null
                ? null
                : MinecraftMathAdapter.toMinecraft(projected);
    }
}
