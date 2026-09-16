package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/** Cross-tick face-selection continuity, revalidated against each operation's
 * scene. It contains no collider/scene reference and never owns gameplay ground.
 * A real dynamic face can retain its address; only trusted static voxel planes
 * can anchor continuous collision-axis changes. */
public record RestingContactSnapshot(
        Vec3 normal,
        Vec3 surfaceVelocity,
        Vec3 contactPoint,
        GravitySupportContact.SupportGeometryKind geometryKind,
        Optional<BlockPos> supportBlock,
        long gameTick,
        cc.sighs.gravityengine.gravity.collision.SupportFaceIdentity faceIdentity
) {
    public RestingContactSnapshot(Vec3 normal, Vec3 surfaceVelocity, Vec3 contactPoint,
            GravitySupportContact.SupportGeometryKind geometryKind, Optional<BlockPos> supportBlock, long gameTick) {
        this(normal, surfaceVelocity, contactPoint, geometryKind, supportBlock, gameTick, null);
    }

    /**
     * Maximum support-surface speed still treated as static terrain.
     */
    public static final double STATIC_SUPPORT_MAX_SPEED =
            1.0E-6D;

    public RestingContactSnapshot {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(
                surfaceVelocity,
                "surfaceVelocity"
        );
        Objects.requireNonNull(
                contactPoint,
                "contactPoint"
        );
        Objects.requireNonNull(
                geometryKind,
                "geometryKind"
        );
        Objects.requireNonNull(
                supportBlock,
                "supportBlock"
        );

        requireFinite(
                normal,
                "normal"
        );
        requireFinite(
                surfaceVelocity,
                "surfaceVelocity"
        );
        requireFinite(
                contactPoint,
                "contactPoint"
        );

        supportBlock =
                supportBlock.map(
                        Objects::requireNonNull
                );

        if (Math.abs(
                normal.lengthSqr() - 1.0D
        ) > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "resting support normal must be normalized: "
                            + normal
            );
        }

        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative"
            );
        }
    }

    public boolean staticSupport() {
        return surfaceVelocity.length()
                <= STATIC_SUPPORT_MAX_SPEED;
    }

    /**
     * A completed support publication is step-start evidence only for its own
     * tick or the immediately following logical step.
     */
    public boolean usableAtStepStart(long gameTick) {
        return gameTick >= this.gameTick
                && gameTick - this.gameTick <= 1L;
    }

    /**
     * True only for a normal/witness pair proven against the final-position
     * finite static BlockObstacle face. Rounded contact does not define that plane.
     */
    public boolean planePreservationEligible() {
        return geometryKind
                == GravitySupportContact
                .SupportGeometryKind
                .TRUSTED_CURRENT_BLOCK_FACE
                && GravitySupportContact.blockFaceIndex(new org.joml.Vector3d(normal.x, normal.y, normal.z)) >= 0
                && (faceIdentity == null || faceIdentity.provesBlockFace(
                        new org.joml.Vector3d(normal.x, normal.y, normal.z),
                        new org.joml.Vector3d(contactPoint.x, contactPoint.y, contactPoint.z)));
    }

    private static void requireFinite(
            Vec3 value,
            String name
    ) {
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: "
                            + value
            );
        }
    }
}
