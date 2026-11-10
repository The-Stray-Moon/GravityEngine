package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/**
 * Explicit authorization and capture bounds for one outer {@code TRAVEL}
 * operation.
 *
 * <p>Actor authorization validates actor-generated movement before the
 * collision request is assembled. Resolved capture bounds additionally include
 * engine-owned support transport so the one captured scene covers the actual
 * trajectory submitted to the solver.</p>
 */
public record TravelCapturePlan(
        Aabb3d initialBounds,
        Vec3d actorMovementLower,
        Vec3d actorMovementUpper,
        Vec3d resolvedMovementLower,
        Vec3d resolvedMovementUpper,
        CollisionCaptureDomain domain,
        double maxStepHeight
) {
    public static final double MIN_AUTHORIZED_BLOCK_FRICTION = 0.4D;
    public static final double VANILLA_FRICTION_SPEED_NUMERATOR =
            0.21600002D;
    public static final double MAX_GROUNDED_SPEED_FACTOR =
            VANILLA_FRICTION_SPEED_NUMERATOR
                    / (MIN_AUTHORIZED_BLOCK_FRICTION
                    * MIN_AUTHORIZED_BLOCK_FRICTION
                    * MIN_AUTHORIZED_BLOCK_FRICTION);

    private static final double COMPONENT_EPSILON = 1.0E-6D;

    public TravelCapturePlan {
        Objects.requireNonNull(initialBounds, "initialBounds");
        Objects.requireNonNull(actorMovementLower, "actorMovementLower");
        Objects.requireNonNull(actorMovementUpper, "actorMovementUpper");
        Objects.requireNonNull(
                resolvedMovementLower,
                "resolvedMovementLower"
        );
        Objects.requireNonNull(
                resolvedMovementUpper,
                "resolvedMovementUpper"
        );
        Objects.requireNonNull(domain, "domain");
        requireFinite(actorMovementLower, "actorMovementLower");
        requireFinite(actorMovementUpper, "actorMovementUpper");
        requireFinite(resolvedMovementLower, "resolvedMovementLower");
        requireFinite(resolvedMovementUpper, "resolvedMovementUpper");
        requireOrdered(
                actorMovementLower,
                actorMovementUpper,
                "actor"
        );
        requireOrdered(
                resolvedMovementLower,
                resolvedMovementUpper,
                "resolved"
        );
        if (!Double.isFinite(maxStepHeight) || maxStepHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "maxStepHeight must be finite and non-negative: "
                            + maxStepHeight
            );
        }
    }

    public static TravelCapturePlan buildIsotropic(
            Aabb3d initialBounds,
            Vec3d currentVelocity,
            double maxLocomotionContribution,
            double maxStepHeight
    ) {
        return buildIsotropic(
                initialBounds,
                currentVelocity,
                maxLocomotionContribution,
                Vec3d.ZERO,
                maxStepHeight
        );
    }

    public static TravelCapturePlan buildIsotropic(
            Aabb3d initialBounds,
            Vec3d currentVelocity,
            double maxLocomotionContribution,
            Vec3d supportTransportDisplacement,
            double maxStepHeight
    ) {
        Objects.requireNonNull(
                supportTransportDisplacement,
                "supportTransportDisplacement"
        );
        return buildIsotropic(
                initialBounds,
                currentVelocity,
                maxLocomotionContribution,
                supportTransportDisplacement,
                new Aabb3d(
                        supportTransportDisplacement.x(),
                        supportTransportDisplacement.y(),
                        supportTransportDisplacement.z(),
                        supportTransportDisplacement.x(),
                        supportTransportDisplacement.y(),
                        supportTransportDisplacement.z()
                ),
                maxStepHeight
        );
    }

    /**
     * Isotropic actor movement bound plus the exact relative envelope of the
     * support material-point path for this operation.
     *
     * <p>{@code supportRelativeBounds} describes support displacement
     * relative to the operation start. It already contains the endpoint
     * chord when the provider's trajectory is curved, so the resolved range
     * is the componentwise sum of the actor bound and this envelope.</p>
     */
    public static TravelCapturePlan buildIsotropic(
            Aabb3d initialBounds,
            Vec3d currentVelocity,
            double maxLocomotionContribution,
            Vec3d supportTransportDisplacement,
            Aabb3d supportRelativeBounds,
            double maxStepHeight
    ) {
        Objects.requireNonNull(initialBounds, "initialBounds");
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        Objects.requireNonNull(
                supportTransportDisplacement,
                "supportTransportDisplacement"
        );
        Objects.requireNonNull(
                supportRelativeBounds,
                "supportRelativeBounds"
        );
        requireFinite(currentVelocity, "currentVelocity");
        requireFinite(
                supportTransportDisplacement,
                "supportTransportDisplacement"
        );
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
        Vec3d actorLower = cubeLower(currentVelocity, radius);
        Vec3d actorUpper = cubeUpper(currentVelocity, radius);
        Vec3d resolvedLower = actorLower.add(
                supportRelativeBounds.minX(),
                supportRelativeBounds.minY(),
                supportRelativeBounds.minZ()
        );
        Vec3d resolvedUpper = actorUpper.add(
                supportRelativeBounds.maxX(),
                supportRelativeBounds.maxY(),
                supportRelativeBounds.maxZ()
        );

        return new TravelCapturePlan(
                initialBounds,
                actorLower,
                actorUpper,
                resolvedLower,
                resolvedUpper,
                CollisionCaptureDomain.forTranslationRange(
                        initialBounds,
                        resolvedLower,
                        resolvedUpper,
                        maxStepHeight
                ),
                maxStepHeight
        );
    }

    public boolean coversActorMovement(Vec3d actualMovement) {
        return covers(
                actorMovementLower,
                actorMovementUpper,
                actualMovement
        );
    }

    public boolean coversResolvedMovement(Vec3d actualMovement) {
        return covers(
                resolvedMovementLower,
                resolvedMovementUpper,
                actualMovement
        );
    }

    private static boolean covers(
            Vec3d lower,
            Vec3d upper,
            Vec3d actualMovement
    ) {
        Objects.requireNonNull(actualMovement, "actualMovement");
        requireFinite(actualMovement, "actualMovement");

        return actualMovement.x()
                >= lower.x() - COMPONENT_EPSILON
                && actualMovement.x()
                <= upper.x() + COMPONENT_EPSILON
                && actualMovement.y()
                >= lower.y() - COMPONENT_EPSILON
                && actualMovement.y()
                <= upper.y() + COMPONENT_EPSILON
                && actualMovement.z()
                >= lower.z() - COMPONENT_EPSILON
                && actualMovement.z()
                <= upper.z() + COMPONENT_EPSILON;
    }

    private static Vec3d cubeLower(Vec3d center, double radius) {
        return new Vec3d(
                center.x() - radius,
                center.y() - radius,
                center.z() - radius
        );
    }

    private static Vec3d cubeUpper(Vec3d center, double radius) {
        return new Vec3d(
                center.x() + radius,
                center.y() + radius,
                center.z() + radius
        );
    }

    private static void requireOrdered(
            Vec3d lower,
            Vec3d upper,
            String name
    ) {
        if (lower.x() > upper.x() + COMPONENT_EPSILON
                || lower.y() > upper.y() + COMPONENT_EPSILON
                || lower.z() > upper.z() + COMPONENT_EPSILON) {
            throw new IllegalArgumentException(
                    name + " lower must not exceed upper: lower="
                            + lower + ", upper=" + upper
            );
        }
    }

    public static void requireFinite(
            Vec3d vector,
            String name
    ) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }
}
