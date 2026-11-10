package cc.sighs.gravityengine.gravity.look;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Gravity-local replacement for the world-XZ body-heading block of
 * {@code LivingEntity.tick}.
 *
 * <p>Vanilla 21.1.249 derives the walk heading from world {@code dx}/{@code dz}
 * and the walk amount from {@code sqrt(dx*dx + dz*dz) * 3}, then feeds
 * {@code tickHeadTurn(targetBodyYaw, movementAmount)}.  This helper keeps that
 * algorithm and thresholds bit-identical while projecting the displacement
 * onto the active gravity frame's tangent plane (+X = left, +Z = forward), so
 * wall/ceiling gravity locomotion produces a valid heading and amount.</p>
 */
public final class GravityBodyTurnMath {
    private static final float MOVEMENT_THRESHOLD_SQUARED = 0.0025000002F;
    private static final float MOVEMENT_SCALE = 3.0F;
    private static final float BACKWARD_MIN = 95.0F;
    private static final float BACKWARD_MAX = 265.0F;

    private GravityBodyTurnMath() {}

    /** Corrected inputs for the {@code tickHeadTurn} invocation. */
    public record BodyTurnInput(
            float targetBodyYaw,
            float movementAmount
    ) {}

    /** Result of the semantic equivalent of Vanilla tickHeadTurn(FF). */
    public record BodyTurnStep(
            float bodyYaw,
            float animationStep
    ) {}

    /** Entity-boundary resolve with explicit locomotion-only evidence. */
    public static BodyTurnInput resolve(
            LivingEntity entity,
            GravityFrame frame,
            Vec3 semanticViewForwardWorld,
            Vec3 locomotionDisplacementWorld
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(
                semanticViewForwardWorld, "semanticViewForwardWorld");
        Objects.requireNonNull(
                locomotionDisplacementWorld, "locomotionDisplacementWorld");
        return compute(
                frame,
                locomotionDisplacementWorld,
                entity.yBodyRot,
                semanticViewForwardWorld,
                entity.attackAnim
        );
    }

    /**
     * Pure equivalent of the vanilla {@code tick} body-turn block with an
     * explicitly resolved semantic world view vector.  Backward-walk and
     * attack body-turn decisions use the semantic view heading on the gravity
     * tangent plane instead of a raw {@code yRot} scalar that has no absolute
     * meaning under active BodyAttitude.
     */
    public static BodyTurnInput compute(
            GravityFrame frame,
            Vec3 worldDisplacement,
            float currentBodyYaw,
            Vec3 semanticViewForwardWorld,
            float attackAnim
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(worldDisplacement, "worldDisplacement");
        Objects.requireNonNull(
                semanticViewForwardWorld, "semanticViewForwardWorld");
        float viewYaw = semanticViewYaw(
                frame, semanticViewForwardWorld, currentBodyYaw);
        return computeScalarHeading(
                frame, worldDisplacement, currentBodyYaw, viewYaw, attackAnim);
    }

    /** Gravity-tangent semantic view yaw with stable pole fallback. */
    public static float semanticViewYaw(
            GravityFrame frame,
            Vec3 semanticViewForwardWorld,
            float fallbackYaw
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(
                semanticViewForwardWorld, "semanticViewForwardWorld");
        AttitudeSpaceTransform.TangentHeading semantic =
                AttitudeSpaceTransform.gravityTangentHeading(
                        frame,
                        MinecraftMathAdapter.toVec3d(
                                semanticViewForwardWorld
                        ));
        return semantic.tangentLengthSquared()
                <= SEMANTIC_HEADING_EPSILON_SQUARED
                ? fallbackYaw
                : semantic.yawDegrees();
    }

    /**
     * Exact 1.21.1 Vanilla body-yaw smoothing/clamp kernel with the raw
     * {@code getYRot()} dependency replaced by an explicit semantic view yaw.
     */
    public static BodyTurnStep tickHeadTurn(
            float currentBodyYaw,
            float targetBodyYaw,
            float semanticViewYaw,
            float maxHeadRotationRelativeToBody,
            float animationStep
    ) {
        float difference = Mth.wrapDegrees(targetBodyYaw - currentBodyYaw);
        float bodyYaw = currentBodyYaw + difference * 0.3F;
        float headDifference = Mth.wrapDegrees(semanticViewYaw - bodyYaw);
        if (Math.abs(headDifference) > maxHeadRotationRelativeToBody) {
            bodyYaw += headDifference
                    - (float) Mth.sign(headDifference)
                    * maxHeadRotationRelativeToBody;
        }
        if (headDifference < -90.0F || headDifference >= 90.0F) {
            animationStep *= -1.0F;
        }
        return new BodyTurnStep(bodyYaw, animationStep);
    }

    /**
     * Pure equivalent of the vanilla {@code tick} body-turn block, with the
     * world-XZ displacement replaced by the gravity-tangent displacement.
     * Under the default gravity frame every output matches Vanilla exactly.
     *
     * <p>Airborne does NOT suppress the {@code tickHeadTurn} movement amount:
     * in the actual 21.1.249 {@code LivingEntity.tick} bytecode the airborne
     * check zeroes the separate {@code run} animation target (local f8), while
     * {@code f7 = sqrt(dx*dx + dz*dz) * 3} is passed to
     * {@code tickHeadTurn} unchanged.  This method therefore has no
     * {@code onGround} parameter.</p>
     */
    private static BodyTurnInput computeScalarHeading(
            GravityFrame frame,
            Vec3 worldDisplacement,
            float currentBodyYaw,
            float viewYaw,
            float attackAnim
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(worldDisplacement, "worldDisplacement");
        AttitudeSpaceTransform.TangentHeading heading =
                AttitudeSpaceTransform.gravityTangentHeading(
                        frame,
                        MinecraftMathAdapter.toVec3d(
                                worldDisplacement
                        )
                );

        float targetBodyYaw = currentBodyYaw;
        float movementAmount = 0.0F;
        double tangentSqr = heading.tangentLengthSquared();
        if (tangentSqr > MOVEMENT_THRESHOLD_SQUARED) {
            movementAmount = (float) Math.sqrt(tangentSqr) * MOVEMENT_SCALE;
            targetBodyYaw = heading.yawDegrees();
            float difference = Mth.abs(
                    Mth.wrapDegrees(viewYaw) - targetBodyYaw
            );
            if (difference > BACKWARD_MIN && difference < BACKWARD_MAX) {
                targetBodyYaw -= 180.0F;
            }
        }
        if (attackAnim > 0.0F) {
            targetBodyYaw = viewYaw;
        }
        return new BodyTurnInput(targetBodyYaw, movementAmount);
    }

    private static final double SEMANTIC_HEADING_EPSILON_SQUARED =
            1.0E-16D;
}
