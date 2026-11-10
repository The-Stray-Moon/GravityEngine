package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CellPos;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.collision.SupportFaceIdentity;
import java.util.Objects;
import java.util.Optional;

/** Cross-tick face-selection continuity, revalidated against each operation's
 * scene. It contains no collider/scene reference and never owns gameplay ground.
 * A real dynamic face can retain its address; only trusted static voxel planes
 * can anchor continuous collision-axis changes. */
public record RestingContactSnapshot(
        Vec3d normal,
        Vec3d surfaceVelocity,
        Vec3d contactPoint,
        GravitySupportContact.SupportGeometryKind geometryKind,
        Optional<CellPos> supportBlock,
        long gameTick,
        SupportFaceIdentity faceIdentity
) {
    /**
     * Maximum support-surface speed still treated as static terrain.
     */
    public static final double STATIC_SUPPORT_MAX_SPEED =
            1.0E-6D;

    public RestingContactSnapshot {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(surfaceVelocity, "surfaceVelocity");
        Objects.requireNonNull(contactPoint, "contactPoint");
        Objects.requireNonNull(geometryKind, "geometryKind");
        Objects.requireNonNull(supportBlock, "supportBlock");

        requireFinite(normal, "normal");
        requireFinite(surfaceVelocity, "surfaceVelocity");
        requireFinite(contactPoint, "contactPoint");

        supportBlock = supportBlock.map(Objects::requireNonNull);

        if (Math.abs(normal.lengthSquared() - 1.0D) > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "resting support normal must be normalized: " + normal
            );
        }

        if (gameTick < 0L) {
            throw new IllegalArgumentException("gameTick must be non-negative");
        }
    }

    public boolean staticSupport() {
        /*
         * Surface speed is NOT support identity.
         *
         * A kinematic rigid obstacle may be motionless for one publication while
         * still being a dynamic obstacle whose identity must be revalidated
         * against the next captured scene.
         */
        if (faceIdentity != null) {
            return faceIdentity.staticBlockSupport();
        }

        /*
         * Unaddressed contacts may only be treated as static when they
         * actually name a block support.
         */
        return supportBlock.isPresent()
                && surfaceVelocity.length()
                <= STATIC_SUPPORT_MAX_SPEED;
    }

    public boolean dynamicSupport() {
        return faceIdentity != null
                && faceIdentity.dynamicSupport();
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
                == GravitySupportContact.SupportGeometryKind.TRUSTED_CURRENT_BLOCK_FACE
                && GravitySupportContact.blockFaceIndex(normal) >= 0
                && (faceIdentity == null || faceIdentity.provesBlockFace(
                        normal, contactPoint));
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
