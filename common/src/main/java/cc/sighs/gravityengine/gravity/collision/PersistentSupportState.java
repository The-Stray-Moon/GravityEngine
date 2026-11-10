package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned persistent support identity that can survive across ticks.
 *
 * <p>It answers "supported by WHAT, at which local point/feature" instead of a
 * bare {@code supported = true}:</p>
 *
 * <ul>
 *   <li>a stable engine-owned obstacle/feature address
 *       ({@link SupportFaceIdentity}) - static block cell or generic rigid
 *       obstacle source/primitive with its continuity epoch;</li>
 *   <li>the obstacle-local anchor used to derive every later world position;</li>
 *   <li>the obstacle pose and motion-publication revision the anchor was
 *       captured at;</li>
 *   <li>the operation scene revision and game tick of the terminal query that
 *       produced it.</li>
 * </ul>
 *
 * <p>The state is runtime-only. It is deliberately never serialized: a
 * moving-obstacle support has no valid meaning across a world save/load unless
 * the obstacle lifecycle itself is re-established, so the next operation must
 * re-acquire it from a real terminal query.</p>
 *
 * <p>No world-space anchor is stored: a moving obstacle's world point changes
 * every published interval. The durable anchor is obstacle-local, and every
 * later world point is derived by transforming it with the obstacle pose.</p>
 *
 * <p>Loader-neutral: it references engine geometry values only, never a
 * Minecraft entity, block state, moving-world implementation or optional-mod
 * type.</p>
 */
public record PersistentSupportState(
        SupportFaceIdentity identity,
        Vec3d localAnchor,
        Vec3d normal,
        RigidPose obstaclePoseAtCapture,
        long motionRevision,
        long capturedSceneRevision,
        long gameTick,
        GravitySupportContact.SupportGeometryKind geometryKind
) {
    private static final double NORMAL_EPSILON = 1.0E-6D;
    private static final RigidPose STATIC_POSE = new RigidPose(
            Vec3d.ZERO,
            OrthonormalFrame3d.IDENTITY
    );

    public PersistentSupportState {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(localAnchor, "localAnchor");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(obstaclePoseAtCapture, "obstaclePoseAtCapture");
        Objects.requireNonNull(geometryKind, "geometryKind");

        requireFinite(localAnchor, "localAnchor");
        requireFinite(normal, "normal");

        if (Math.abs(normal.lengthSquared() - 1.0D) > NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "persistent support normal must be normalized: " + normal
            );
        }
        if (motionRevision < 0L
                || capturedSceneRevision < 0L
                || gameTick < 0L) {
            throw new IllegalArgumentException(
                    "persistent support revisions/ticks must be non-negative"
            );
        }
        if (identity.staticBlockSupport()
                && (motionRevision != 0L
                || !obstaclePoseAtCapture.equals(STATIC_POSE))) {
            throw new IllegalArgumentException(
                    "static block support carries no rigid motion publication"
            );
        }
    }

    /**
     * Derives persistent support from a terminal endpoint witness and the
     * captured scene it was solved against.
     *
     * <p>Returns empty when the witness has no stable identity
     * ({@code MANIFOLD}/untracked sweep support), when the referenced obstacle
     * is not present in the captured scene, or when the scene cannot be
     * revalidated. First/path contact cannot produce this value: terminal
     * grounding is never inferred from first-hit information.</p>
     */
    public static Optional<PersistentSupportState> from(
            EndpointSupportWitness witness,
            CollisionScene scene
    ) {
        Objects.requireNonNull(witness, "witness");

        SupportFaceIdentity identity = witness.identity();
        if (identity == null || !(scene instanceof CapturedCollisionScene captured)) {
            return Optional.empty();
        }
        if (captured.revision() != witness.sceneRevision()) {
            /* The witness belongs to a different captured scene. */
            return Optional.empty();
        }

        GravitySupportContact contact = witness.support();

        if (identity.staticBlockSupport()) {
            if (!staticIdentityPresent(
                    identity,
                    contact.normal(),
                    contact.contactPoint(),
                    captured
            )) {
                return Optional.empty();
            }
            return Optional.of(
                    new PersistentSupportState(
                            identity,
                            contact.contactPoint(),
                            contact.normal(),
                            STATIC_POSE,
                            0L,
                            witness.sceneRevision(),
                            witness.gameTick(),
                            contact.geometryKind()
                    )
            );
        }

        Optional<DynamicCollisionObstacleSnapshot> found =
                captured.dynamicObstacle(identity.obstacleIdentity());
        if (found.isEmpty()) {
            return Optional.empty();
        }

        DynamicCollisionObstacleSnapshot obstacle = found.get();
        RigidTrajectory motion = obstacle.motion();
        if (obstacle.providerRegistrationEpoch()
                != identity.providerRegistrationEpoch()
                || motion.continuityEpoch()
                != identity.continuityEpoch()) {
            /* The source was replaced: this is a different body. */
            return Optional.empty();
        }

        /* Terminal support is measured at the solved final pose. */
        double terminalTime = 1.0D;
        RigidPose pose = motion.poseAt(terminalTime);
        Vec3d localAnchor = toLocal(pose, contact.contactPoint());

        return Optional.of(
                new PersistentSupportState(
                        identity,
                        localAnchor,
                        contact.normal(),
                        pose,
                        motion.revision(),
                        witness.sceneRevision(),
                        witness.gameTick(),
                        contact.geometryKind()
                )
        );
    }

    /**
     * Revalidates a static support address against the exact captured
     * primitive, not merely the block cell.
     *
     * <p>When the identity carries a discrete world-axis face, the captured
     * primitive bounds and the stored normal/witness must still prove that
     * face. An unresolved/oblique identity deliberately falls back to the
     * stable cell plus captured primitive bounds because no discrete face was
     * ever part of its address.</p>
     */
    public static boolean staticIdentityPresent(
            SupportFaceIdentity identity,
            Vec3d normal,
            Vec3d witness,
            CapturedCollisionScene captured
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(witness, "witness");
        Objects.requireNonNull(captured, "captured");

        if (!identity.staticBlockSupport()) {
            return false;
        }

        Optional<BlockObstacle> primitive =
                captured.blockObstacle(
                        identity.block(),
                        identity.voxelPiece()
                );
        if (primitive.isEmpty()) {
            return false;
        }

        return !identity.hasDiscreteFace()
                || identity.provesBlockFace(normal, witness);
    }

    public boolean staticSupport() {
        return identity.staticBlockSupport();
    }

    public boolean movingObstacleSupport() {
        return identity.dynamicSupport();
    }

    /** Stable diagnostic identity of the persistent support address. */
    public String obstacleId() {
        return identity.stableId();
    }

    /** World-space anchor of this support on an arbitrary obstacle pose. */
    public Vec3d anchorAt(RigidPose pose) {
        return pose.transformPoint(localAnchor);
    }

    /**
     * Converts one world point into the obstacle-local frame of {@code pose}.
     * Exposed for focused tests and for callers that must re-anchor support.
     */
    public static Vec3d toLocal(RigidPose pose, Vec3d worldPoint) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(worldPoint, "worldPoint");
        Quatd inverse = BodyOrientation3d
                .quaternion(pose.orientation())
                .conjugate();
        return inverse.transform(worldPoint.subtract(pose.center()));
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
