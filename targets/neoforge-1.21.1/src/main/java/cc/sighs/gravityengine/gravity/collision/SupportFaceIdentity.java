package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;

/** Stable local face address. Publication revision can advance without changing a
 * feature; source/primitive replacement or discontinuity changes its identity. */
public record SupportFaceIdentity(BlockPos block, Aabb3d voxelPiece, long sourceId,
                                  long primitiveId, long continuityEpoch, int face) {
    /** The address alone is selection continuity, not proof of a planar contact. */
    public boolean provesBlockFace(org.joml.Vector3d normal, org.joml.Vector3d witness) {
        return voxelPiece != null && face == GravitySupportContact.blockFaceIndex(normal)
                && GravitySupportContact.matchesBlockFace(voxelPiece, normal, witness);
    }
    public SupportFaceIdentity(BlockPos block, Aabb3d voxelPiece, long sourceId, int face) {
        this(block, voxelPiece, sourceId, 0, 0, face);
    }
    public SupportFaceIdentity {
        if (face < 0 || face >= 6) throw new IllegalArgumentException("face index");
        if ((block == null) != (voxelPiece == null)) throw new IllegalArgumentException("voxel address");
        if (block != null) block = block.immutable();
    }
    public static SupportFaceIdentity dynamic(EntityObstacle obstacle, int face) {
        return new SupportFaceIdentity(null, null, obstacle.sourceId(), obstacle.primitiveId(),
                obstacle.motion().continuityEpoch(), face);
    }
}
