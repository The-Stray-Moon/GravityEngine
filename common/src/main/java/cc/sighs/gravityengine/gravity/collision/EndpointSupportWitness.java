package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Engine-owned terminal support evidence produced from the solved final body
 * pose.
 *
 * <p>A first/path collision is deliberately not represented here: path contact
 * may never be promoted into terminal support. The type exists so callers
 * cannot accidentally treat "something was hit during the sweep" as "the body
 * rests here now".</p>
 *
 * <p>{@code sceneRevision} binds the witness to the exact captured scene and
 * final-pose query that produced it. A persistent support state derived from
 * this witness carries the same binding, so a later operation cannot silently
 * re-anchor to a different scene revision.</p>
 */
public record EndpointSupportWitness(
        GravitySupportContact support,
        long gameTick,
        long sceneRevision
) {
    public EndpointSupportWitness {
        Objects.requireNonNull(support, "support");
        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: " + gameTick
            );
        }
        if (sceneRevision < 0L) {
            throw new IllegalArgumentException(
                    "sceneRevision must be non-negative: " + sceneRevision
            );
        }
    }

    public SupportFaceIdentity identity() {
        return support.faceIdentity();
    }

    /** World-space support witness produced by the final-pose query. */
    public Vec3d worldPoint() {
        return support.contactPoint();
    }

    /** Outward support normal at the final-pose query. */
    public Vec3d normal() {
        return support.normal();
    }

    /** Surface material velocity at the final-pose query (blocks/tick). */
    public Vec3d surfaceVelocity() {
        return support.surfaceVelocity();
    }

    /**
     * Stable diagnostic identity of the supported obstacle/feature.
     *
     * <p>Empty means the witness has no cross-tick identity. The local anchor
     * and obstacle revision are deliberately owned by the captured scene and
     * are materialized into {@link PersistentSupportState} by
     * {@link PersistentSupportState#from(EndpointSupportWitness, CollisionScene)}.</p>
     */
    public String obstacleId() {
        return support.faceIdentity() == null
                ? ""
                : support.faceIdentity().stableId();
    }

    /** Stable engine-owned identity is required for persistent support. */
    public boolean hasStableIdentity() {
        return support.faceIdentity() != null;
    }
}
