package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Conservative authorization bound for one outer {@code TRAVEL} scene.
 *
 * <p>The outer scene must cover the actual nested {@code Entity.move}
 * request:
 *
 * <pre>
 * current deltaMovement + current-tick locomotion contribution
 * </pre>
 *
 * The locomotion contribution has a bounded magnitude, but its world-space
 * direction belongs to the operation's selected {@code GravityFrame}. That
 * frame may legitimately differ from the previously installed frame,
 * especially in radial or continuously rotating gravity.
 *
 * Therefore this authorization is deliberately orientation-independent:
 * every world-space vector whose locomotion delta has magnitude at most
 * {@code maxLocomotionContribution} is covered by a componentwise cube around
 * the current velocity. The cube is conservative but deterministic and does
 * not predict the next operation frame.</p>
 */
public record TravelCapturePlan(
        Aabb3d initialBounds,
        Vec3 movementLower,
        Vec3 movementUpper,
        CollisionCaptureDomain domain,
        double maxStepHeight
) {
    /** Lowest vanilla block slipperiness admitted by the grounded speed cap. */
    public static final double MIN_AUTHORIZED_BLOCK_FRICTION = 0.4D;

    /** Vanilla grounded-speed numerator. */
    public static final double VANILLA_FRICTION_SPEED_NUMERATOR =
            0.21600002D;

    /**
     * Largest grounded speed factor over the authorized vanilla friction
     * range: 0.21600002 / 0.4^3.
     */
    public static final double MAX_GROUNDED_SPEED_FACTOR =
            VANILLA_FRICTION_SPEED_NUMERATOR
                    / (MIN_AUTHORIZED_BLOCK_FRICTION
                    * MIN_AUTHORIZED_BLOCK_FRICTION
                    * MIN_AUTHORIZED_BLOCK_FRICTION);

    private static final double COMPONENT_EPSILON = 1.0E-6D;

    public TravelCapturePlan {
        Objects.requireNonNull(initialBounds, "initialBounds");
        Objects.requireNonNull(movementLower, "movementLower");
        Objects.requireNonNull(movementUpper, "movementUpper");
        Objects.requireNonNull(domain, "domain");

        requireFinite(movementLower, "movementLower");
        requireFinite(movementUpper, "movementUpper");

        if (!Double.isFinite(maxStepHeight) || maxStepHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "maxStepHeight must be finite and non-negative: "
                            + maxStepHeight
            );
        }

        if (movementLower.x > movementUpper.x + COMPONENT_EPSILON
                || movementLower.y > movementUpper.y + COMPONENT_EPSILON
                || movementLower.z > movementUpper.z + COMPONENT_EPSILON) {
            throw new IllegalArgumentException(
                    "movementLower must not exceed movementUpper: lower="
                            + movementLower + ", upper=" + movementUpper
            );
        }

        movementLower = new Vec3(
                movementLower.x,
                movementLower.y,
                movementLower.z
        );
        movementUpper = new Vec3(
                movementUpper.x,
                movementUpper.y,
                movementUpper.z
        );
    }

    /**
     * Constructs an orientation-independent authorization cube around the
     * current velocity.
     *
     * <p>For every legal locomotion contribution {@code d} satisfying
     * {@code |d| <= radius}, each Cartesian component also satisfies
     * {@code |d.axis| <= radius}. Therefore this componentwise range covers
     * every possible orientation of the operation frame.</p>
     */
    public static TravelCapturePlan buildIsotropic(
            Aabb3d initialBounds,
            Vec3 currentVelocity,
            double maxLocomotionContribution,
            double maxStepHeight
    ) {
        Objects.requireNonNull(initialBounds, "initialBounds");
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        requireFinite(currentVelocity, "currentVelocity");

        if (!Double.isFinite(maxLocomotionContribution)
                || maxLocomotionContribution < 0.0D) {
            throw new IllegalArgumentException(
                    "maxLocomotionContribution must be finite "
                            + "and non-negative: "
                            + maxLocomotionContribution
            );
        }
        if (!Double.isFinite(maxStepHeight) || maxStepHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "maxStepHeight must be finite and non-negative: "
                            + maxStepHeight
            );
        }

        double radius = maxLocomotionContribution;

        Vec3 lower = new Vec3(
                currentVelocity.x - radius,
                currentVelocity.y - radius,
                currentVelocity.z - radius
        );
        Vec3 upper = new Vec3(
                currentVelocity.x + radius,
                currentVelocity.y + radius,
                currentVelocity.z + radius
        );

        CollisionCaptureDomain domain =
                CollisionCaptureDomain.forTranslationRange(
                        initialBounds,
                        MinecraftGeometryAdapter.toJoml(
                                lower,
                                new org.joml.Vector3d()
                        ),
                        MinecraftGeometryAdapter.toJoml(
                                upper,
                                new org.joml.Vector3d()
                        ),
                        maxStepHeight
                );

        return new TravelCapturePlan(
                initialBounds,
                lower,
                upper,
                domain,
                maxStepHeight
        );
    }

    /**
     * Checks the actual request against the same conservative cube from which
     * the CollisionCaptureDomain was built.
     */
    public boolean covers(Vec3 actualMovement) {
        Objects.requireNonNull(actualMovement, "actualMovement");
        requireFinite(actualMovement, "actualMovement");

        return actualMovement.x
                >= movementLower.x - COMPONENT_EPSILON
                && actualMovement.x
                <= movementUpper.x + COMPONENT_EPSILON

                && actualMovement.y
                >= movementLower.y - COMPONENT_EPSILON
                && actualMovement.y
                <= movementUpper.y + COMPONENT_EPSILON

                && actualMovement.z
                >= movementLower.z - COMPONENT_EPSILON
                && actualMovement.z
                <= movementUpper.z + COMPONENT_EPSILON;
    }

    public static void requireFinite(
            Vec3 vector,
            String name
    ) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }
}