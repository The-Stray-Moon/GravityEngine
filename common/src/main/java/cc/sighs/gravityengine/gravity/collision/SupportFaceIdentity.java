package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

/** Stable local face address. Publication revision can advance without changing a
 * feature; source/primitive replacement, provider-instance replacement or
 * discontinuity changes its identity. */
public record SupportFaceIdentity(
        CellPos block,
        Aabb3d voxelPiece,
        String providerNamespace,
        long providerRegistrationEpoch,
        long sourceId,
        long primitiveId,
        long continuityEpoch,
        int face
) {
    /**
     * Feature identity is a source/primitive identity only: the contact normal
     * does not resolve to one discrete world-axis face (for example an oblique
     * OBB corner). Such an address can still survive across ticks and own
     * transport, but it never authorizes a reusable support plane.
     */
    public static final int NO_FACE = -1;

    /** The address alone is selection continuity, not proof of a planar contact. */
    public boolean provesBlockFace(Vec3d normal, Vec3d witness) {
        return voxelPiece != null && face == GravitySupportContact.blockFaceIndex(normal)
                && GravitySupportContact.matchesBlockFace(voxelPiece, normal, witness);
    }
    public SupportFaceIdentity(CellPos block, Aabb3d voxelPiece, long sourceId, int face) {
        this(
                block,
                voxelPiece,
                RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE,
                DynamicCollisionObstacleSnapshot
                        .PROVIDER_LOCAL_REGISTRATION_EPOCH,
                sourceId,
                0,
                0,
                face
        );
    }
    public SupportFaceIdentity {
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
        if (face < NO_FACE || face >= 6) throw new IllegalArgumentException("face index");
        if ((block == null) != (voxelPiece == null)) throw new IllegalArgumentException("voxel address");
    }

    /** Whether this address names one discrete world-axis face. */
    public boolean hasDiscreteFace() {
        return face != NO_FACE;
    }

    public static SupportFaceIdentity dynamic(EntityObstacle obstacle, int face) {
        return new SupportFaceIdentity(
                null,
                null,
                obstacle.providerNamespace(),
                obstacle.snapshot().providerRegistrationEpoch(),
                obstacle.sourceId(),
                obstacle.primitiveId(),
                obstacle.motion().continuityEpoch(),
                face
        );
    }

    /** Dynamic source/primitive address with an unresolved face. */
    public static SupportFaceIdentity dynamic(
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            int face
    ) {
        return new SupportFaceIdentity(
                null,
                null,
                RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE,
                DynamicCollisionObstacleSnapshot
                        .PROVIDER_LOCAL_REGISTRATION_EPOCH,
                sourceId,
                primitiveId,
                continuityEpoch,
                face
        );
    }

    public static SupportFaceIdentity dynamic(
            String providerNamespace,
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            int face
    ) {
        return new SupportFaceIdentity(
                null,
                null,
                providerNamespace,
                DynamicCollisionObstacleSnapshot
                        .PROVIDER_LOCAL_REGISTRATION_EPOCH,
                sourceId,
                primitiveId,
                continuityEpoch,
                face
        );
    }

    public static SupportFaceIdentity dynamic(
            String providerNamespace,
            long providerRegistrationEpoch,
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            int face
    ) {
        return new SupportFaceIdentity(
                null,
                null,
                providerNamespace,
                providerRegistrationEpoch,
                sourceId,
                primitiveId,
                continuityEpoch,
                face
        );
    }

    /** The rigid-obstacle identity carried by this support address. */
    public RigidObstacleIdentity obstacleIdentity() {
        if (staticBlockSupport()) {
            throw new IllegalStateException(
                    "static block support has no rigid obstacle identity"
            );
        }
        return new RigidObstacleIdentity(
                providerNamespace,
                providerRegistrationEpoch,
                sourceId,
                primitiveId,
                continuityEpoch
        );
    }

    public boolean staticBlockSupport() {
        return block != null;
    }

    public boolean dynamicSupport() {
        return block == null;
    }

    /**
     * Stable diagnostic identity of this address.
     *
     * <p>This is a human-readable projection for diagnostics and support
     * lifecycle logs. Equality and persistence always use the record
     * components, never this string.</p>
     */
    public String stableId() {
        if (staticBlockSupport()) {
            return "block:"
                    + block.x() + ","
                    + block.y() + ","
                    + block.z() + ":"
                    + face;
        }
        return "rigid:"
                + providerNamespace + ":"
                + providerRegistrationEpoch + ":"
                + sourceId + ":"
                + primitiveId + ":"
                + continuityEpoch + ":"
                + face;
    }
}
