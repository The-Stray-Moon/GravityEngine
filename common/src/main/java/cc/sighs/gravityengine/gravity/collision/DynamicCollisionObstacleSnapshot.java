package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/**
 * Immutable exact primitive and completed motion. Ordinary capsules retain
 * their captured gravity axis and publish translation only; prescribed
 * external OBBs may rotate.
 *
 * <p>Identity components are engine-owned: the provider namespace, the
 * registry-assigned provider registration epoch, the adapter-assigned stable
 * source/primitive ids and the publication continuity epoch. Replacement of a
 * provider instance changes the registration epoch; replacement of a live
 * source changes the source id or continuity epoch.</p>
 *
 * <p>{@code providerRegistrationEpoch == 0} means "provider local": the
 * publication has not (yet) been normalized by the owning registry. The
 * registry always rebinds captured publications to its own registration
 * epoch, so a persisted identity never carries the provider-local default.</p>
 *
 * <p>Cached bounds are conservative query evidence, never exact occupancy.</p>
 */
public final class DynamicCollisionObstacleSnapshot {
    /**
     * Provider-local registration epoch used before the routing registry
     * normalizes a publication to its own engine-owned epoch.
     */
    public static final long PROVIDER_LOCAL_REGISTRATION_EPOCH = 0L;

    private final String providerNamespace;
    private final long providerRegistrationEpoch;
    private final long sourceId, primitiveId;
    private final CollisionBody exactLocalBody;
    private final RigidTrajectory motion;
    private final Aabb3d initialBounds, operationSweptBounds;

    public DynamicCollisionObstacleSnapshot(long sourceId, long primitiveId,
            CollisionBody localBody, RigidTrajectory motion) {
        this(
                RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE,
                PROVIDER_LOCAL_REGISTRATION_EPOCH,
                sourceId,
                primitiveId,
                localBody,
                motion
        );
    }

    public DynamicCollisionObstacleSnapshot(
            String providerNamespace,
            long sourceId,
            long primitiveId,
            CollisionBody localBody,
            RigidTrajectory motion
    ) {
        this(
                providerNamespace,
                PROVIDER_LOCAL_REGISTRATION_EPOCH,
                sourceId,
                primitiveId,
                localBody,
                motion
        );
    }

    public DynamicCollisionObstacleSnapshot(
            String providerNamespace,
            long providerRegistrationEpoch,
            long sourceId,
            long primitiveId,
            CollisionBody localBody,
            RigidTrajectory motion
    ) {
        if (providerNamespace == null
                || providerNamespace.isBlank()) {
            throw new IllegalArgumentException(
                    "providerNamespace must be non-blank"
            );
        }
        if (providerRegistrationEpoch < 0L) {
            throw new IllegalArgumentException(
                    "providerRegistrationEpoch must be non-negative: "
                            + providerRegistrationEpoch
            );
        }
        this.providerNamespace = providerNamespace;
        this.providerRegistrationEpoch =
                providerRegistrationEpoch;
        this.sourceId = sourceId; this.primitiveId = primitiveId;
        this.exactLocalBody = Objects.requireNonNull(localBody, "localBody");
        this.motion = Objects.requireNonNull(motion, "motion");
        if (!(localBody instanceof OrientedBox) && (motion.requiresGeneralSweep()
                || !motion.start().orientation().equals(cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY)))
            throw new IllegalArgumentException("non-rigid exact entity geometry requires translation-only publication");
        this.initialBounds = exactBodyAt(0).enclosingAabb();
        this.operationSweptBounds = sweptBounds(0, 1);
    }
    public String providerNamespace() { return providerNamespace; }
    public long providerRegistrationEpoch() {
        return providerRegistrationEpoch;
    }
    public long sourceId() { return sourceId; }
    public long primitiveId() { return primitiveId; }
    public RigidObstacleIdentity identity() { return RigidObstacleIdentity.of(this); }

    /**
     * Rebind registry-owned provider provenance without changing geometry.
     *
     * <p>Only the owning routing registry may call this: it is the sole
     * allocator of {@code providerRegistrationEpoch}.</p>
     */
    public DynamicCollisionObstacleSnapshot withProviderIdentity(
            String namespace,
            long providerRegistrationEpoch
    ) {
        if (namespace == null) {
            throw new IllegalArgumentException(
                    "providerNamespace must be non-null"
            );
        }
        if (providerNamespace.equals(namespace)
                && this.providerRegistrationEpoch
                == providerRegistrationEpoch) {
            return this;
        }
        return new DynamicCollisionObstacleSnapshot(
                namespace,
                providerRegistrationEpoch,
                sourceId,
                primitiveId,
                exactLocalBody,
                motion
        );
    }
    /** Only the prescribed OBB motion kernel may request a rigid primitive. */
    public OrientedBox localBody() {
        if (exactLocalBody instanceof OrientedBox box) return box;
        throw new IllegalStateException("exact character is not a rigid OBB");
    }
    public CollisionBody exactBodyAt(double time) {
        return exactLocalBody instanceof OrientedBox box ? motion.bodyAt(box, time)
                : exactLocalBody.move(motion.poseAt(time).center());
    }
    public double maximumPointDisplacement() {
        return hasRigidBox() ? motion.maximumPointDisplacement(localBody()) : motion.displacementForInterval().length();
    }
    public boolean hasRigidBox() { return exactLocalBody instanceof OrientedBox; }
    public RigidTrajectory motion() { return motion; }
    public OrientedBox bodyAt(double time) { return motion.bodyAt(localBody(), time); }
    public Aabb3d initialBounds() { return initialBounds; }
    public Aabb3d operationSweptBounds() { return operationSweptBounds; }
    public Aabb3d sweptBounds(double lo, double hi) {
        if (lo == 0 && hi == 1 && operationSweptBounds != null) return operationSweptBounds;
        return exactLocalBody instanceof OrientedBox box ? motion.sweptBounds(box, lo, hi)
                : exactBodyAt(lo).enclosingAabb().expandTowards(motion.centerDisplacement(lo, hi));
    }
    @Override public boolean equals(Object other) {
        return other instanceof DynamicCollisionObstacleSnapshot b
                && providerNamespace.equals(b.providerNamespace)
                && providerRegistrationEpoch
                == b.providerRegistrationEpoch
                && sourceId == b.sourceId
                && primitiveId == b.primitiveId && exactLocalBody.equals(b.exactLocalBody) && motion.equals(b.motion);
    }
    @Override public int hashCode() {
        return Objects.hash(
                providerNamespace,
                providerRegistrationEpoch,
                sourceId,
                primitiveId,
                exactLocalBody,
                motion
        );
    }
}
