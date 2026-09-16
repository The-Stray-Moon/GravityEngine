package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.Objects;

/**
 * Bounded material-point discovery and immutable rigid-sweep candidate policy.
 *
 * <p>Operates on neutral {@link Aabb3d} bounds; the Minecraft scene builder
 * converts entity bounding boxes at its boundary before calling here.</p>
 *
 * <p>The 16-block expansion discovers only providers that explicitly opt into
 * same-interval motion. Ordinary entities are filtered by current geometry.</p>
 */
public final class DynamicEntityBroadphasePolicy {
    /** Discovery reach of any material point, including angular travel. */
    public static final double MAX_RIGID_SWEPT_REACH = 16.0D;

    private DynamicEntityBroadphasePolicy() {}

    public static void validatePublication(CollisionSurfaceMotionProvider.MotionSnapshot publication,
            Aabb3d discoveryBounds, cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext time) {
        double declared = publication.declaredMaximumPointDisplacement();
        if (exceedsDiscoverableBound(declared))
            throw new CollisionSceneCoverageException("rigid publication exceeds discoverable material-point reach");
        validateCaptured(publication.primitives(), time);
        for (var primitive : publication.primitives()) {
            Aabb3d initial = primitive.initialBounds();
            if (initial.minX() < discoveryBounds.minX() - CollisionTolerances.CONTACT_SKIN
                    || initial.minY() < discoveryBounds.minY() - CollisionTolerances.CONTACT_SKIN
                    || initial.minZ() < discoveryBounds.minZ() - CollisionTolerances.CONTACT_SKIN
                    || initial.maxX() > discoveryBounds.maxX() + CollisionTolerances.CONTACT_SKIN
                    || initial.maxY() > discoveryBounds.maxY() + CollisionTolerances.CONTACT_SKIN
                    || initial.maxZ() > discoveryBounds.maxZ() + CollisionTolerances.CONTACT_SKIN)
                throw new CollisionSceneCoverageException("rigid geometry outside publisher discovery bounds");
            if (primitive.maximumPointDisplacement()
                    > declared + CollisionTolerances.CONTACT_SKIN)
                throw new CollisionSceneCoverageException("rigid publication exceeded declared material-point reach");
        }
    }

    /** Validate once at capture, before any query can select/filter a primitive. */
    public static void validateCaptured(java.util.List<DynamicCollisionObstacleSnapshot> primitives,
            cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext time) {
        record Key(long source, long primitive) {}
        var keys = new java.util.HashSet<Key>();
        var publications = new java.util.HashMap<Long, RigidMotionSnapshot>();
        for (var primitive : primitives) {
            var motion = primitive.motion();
            if (motion.tick() != time.gameTick() || motion.intervalTicks() != time.intervalTicks())
                throw new CollisionSceneCoverageException("rigid publication tick/interval mismatch");
            if (!keys.add(new Key(primitive.sourceId(), primitive.primitiveId())))
                throw new CollisionSceneCoverageException("duplicate rigid primitive identity");
            var previous = publications.putIfAbsent(primitive.sourceId(), motion);
            if (previous != null && !previous.equals(motion))
                throw new CollisionSceneCoverageException("mixed rigid body publication/revision");
            if (primitive.maximumPointDisplacement()
                    > MAX_RIGID_SWEPT_REACH + CollisionTolerances.CONTACT_SKIN)
                throw new CollisionSceneCoverageException("rigid sweep exceeds discovery reach");
        }
    }

    public static Aabb3d candidateQueryBounds(Aabb3d characterSwept) {
        Objects.requireNonNull(characterSwept, "characterSwept");
        return characterSwept.inflate(
                MAX_RIGID_SWEPT_REACH + CollisionTolerances.CONTACT_SKIN
        );
    }

    /**
     * Conservative provider discovery using the provider's declared
     * material-point displacement bound, before the exact authoritative snapshot
     * displacement is used for the swept-AABB filter.
     */
    public static boolean declaredMotionCanReachCorridor(
            Aabb3d characterSwept,
            Aabb3d otherCurrentBounds,
            double declaredMaximumDisplacement
    ) {
        if (!Double.isFinite(declaredMaximumDisplacement)
                || declaredMaximumDisplacement < 0.0D) {
            throw new IllegalArgumentException(
                    "declaredMaximumDisplacement must be finite and non-negative"
            );
        }
        return otherCurrentBounds.inflate(
                declaredMaximumDisplacement + CollisionTolerances.CONTACT_SKIN
        ).intersects(characterSwept.inflate(CollisionTolerances.CONTACT_SKIN));
    }

    public static boolean exceedsDiscoverableBound(double declaredMaximum) {
        return !Double.isFinite(declaredMaximum)
                || declaredMaximum > MAX_RIGID_SWEPT_REACH;
    }

}
