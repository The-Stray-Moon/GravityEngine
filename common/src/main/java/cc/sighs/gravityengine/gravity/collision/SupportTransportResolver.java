package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned support transport for a persistent support against one captured
 * collision scene.
 *
 * <p>This is the generic moving/rotating support model. It is not a
 * post-processing hack and it does not depend on an optional compatibility
 * layer telling it a "remaining motion": translation, rotation and the
 * resulting surface-point velocity are derived from the engine's own
 * {@link RigidTrajectory} publication.</p>
 *
 * <p>Invariants enforced here:</p>
 *
 * <ul>
 *   <li>the referenced obstacle must be present in the resolving scene with a
 *       matching continuity epoch - otherwise the caller must clear the
 *       persistent support;</li>
 *   <li>the publication pose at interval start must match the captured pose -
 *       a discontinuity invalidates support instead of teleporting the
 *       actor;</li>
 *   <li>the same motion revision is never transported twice; a repeated
 *       resolution returns a valid resting transport with zero
 *       displacement;</li>
 *   <li>a scene revision that is not newer than the capture revision cannot be
 *       used to transport the same operation.</li>
 * </ul>
 */
public final class SupportTransportResolver {
    /** Position tolerance for publication-pose continuity. */
    private static final double POSE_POSITION_TOLERANCE = 1.0E-7D;

    /** Angular tolerance (radians) for publication-pose continuity. */
    private static final double POSE_ANGLE_TOLERANCE = 1.0E-7D;

    private SupportTransportResolver() {}

    /**
     * Resolves the current world-space anchor of a persistent support against
     * one exact rigid-obstacle publication.
     *
     * <p>This is the direct obstacle form of the transport rule: the stored
     * local anchor is transformed by the publication's completed end pose. It
     * does not mutate support state and does not perform scene revalidation;
     * callers that need continuity checks must use
     * {@link #resolve(PersistentSupportState, CollisionScene)} or
     * {@link #resolve(PersistentSupportState, DynamicCollisionObstacleSnapshot, double, long)}.</p>
     *
     * @throws IllegalStateException when the publication does not describe the
     *         support's obstacle identity or continuity epoch
     */
    public static Vec3d resolve(
            PersistentSupportState support,
            DynamicCollisionObstacleSnapshot obstacle
    ) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(obstacle, "obstacle");

        if (support.staticSupport()
                || !support.identity().obstacleIdentity().matches(obstacle)) {
            throw new IllegalStateException(
                    "support obstacle mismatch"
            );
        }

        return support.anchorAt(
                obstacle.motion().poseAt(1.0D)
        );
    }

    /**
     * Resolves transport of {@code support} for the operation captured by
     * {@code scene}.
     *
     * <p>Empty means "support cannot be revalidated": the obstacle/block is
     * absent, replaced, discontinuous or outside a capturable scene. Callers
     * must clear persistent support in that case rather than inventing
     * grounding.</p>
     */
    public static Optional<SupportTransport> resolve(
            PersistentSupportState support,
            CollisionScene scene
    ) {
        Objects.requireNonNull(support, "support");

        if (!(scene instanceof CapturedCollisionScene captured)) {
            return Optional.empty();
        }

        double intervalTicks = captured.time().intervalTicks();
        long sceneTick = captured.time().gameTick();

        if (support.staticSupport()) {
            if (!PersistentSupportState.staticIdentityPresent(
                    support.identity(),
                    support.normal(),
                    support.localAnchor(),
                    captured
            )) {
                return Optional.empty();
            }
            return Optional.of(
                    SupportTransport.resting(intervalTicks, 0L, sceneTick)
            );
        }

        Optional<DynamicCollisionObstacleSnapshot> found =
                captured.dynamicObstacle(support.identity().obstacleIdentity());
        if (found.isEmpty()) {
            return Optional.empty();
        }

        RigidTrajectory motion = found.get().motion();
        if (found.get().providerRegistrationEpoch()
                != support.identity().providerRegistrationEpoch()
                || motion.continuityEpoch()
                != support.identity().continuityEpoch()) {
            return Optional.empty();
        }
        if (motion.revision() < support.motionRevision()) {
            /*
             * A lower revision is stale temporal order, not a new interval.
             * Failing closed clears persistent support instead of inventing a
             * transport from a rolled-back publication.
             */
            return Optional.empty();
        }

        /*
         * A publication that has not advanced describes the interval whose
         * transport was already accounted for. Returning a valid resting
         * transport keeps the support alive without applying motion twice.
         */
        if (motion.revision() == support.motionRevision()
                || captured.revision() <= support.capturedSceneRevision()) {
            return Optional.of(
                    SupportTransport.resting(
                            intervalTicks,
                            support.motionRevision(),
                            sceneTick
                    )
            );
        }

        RigidPose start = motion.poseAt(0.0D);
        if (!poseContinuous(start, support.obstaclePoseAtCapture())) {
            return Optional.empty();
        }

        RigidPose end =
                motion.poseAt(1.0D);

        Vec3d startAnchor =
                support.anchorAt(start);

        Vec3d endAnchor =
                support.anchorAt(end);

        Vec3d displacement =
                endAnchor.subtract(startAnchor);

        Vec3d startSurfaceVelocity =
                motion.velocityAt(
                        startAnchor,
                        0.0D
                );

        Vec3d endSurfaceVelocity =
                motion.velocityAt(
                        endAnchor,
                        1.0D
                );

        return Optional.of(
                new SupportTransport(
                        displacement,
                        startSurfaceVelocity,
                        endSurfaceVelocity,
                        Optional.of(
                                new SupportMotionTrajectory(
                                        motion,
                                        support.localAnchor()
                                )
                        ),
                        intervalTicks,
                        motion.revision(),
                        sceneTick
                )
        );
    }

    /**
     * Resolution against one already-published rigid obstacle snapshot.
     *
     * <p>This is the capture-preflight form. It is deliberately scene-free:
     * the caller uses the returned displacement only to widen the
     * {@link CollisionCaptureDomain}. The operation then revalidates against
     * its one captured scene before the transport can affect the solve.</p>
     */
    public static Optional<SupportTransport> resolve(
            PersistentSupportState support,
            DynamicCollisionObstacleSnapshot obstacle,
            double intervalTicks,
            long gameTick
    ) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(obstacle, "obstacle");
        if (support.staticSupport()
                || !support.identity().obstacleIdentity().matches(obstacle)) {
            return Optional.empty();
        }

        RigidTrajectory motion = obstacle.motion();
        if (motion.continuityEpoch()
                != support.identity().continuityEpoch()) {
            return Optional.empty();
        }
        if (motion.revision() < support.motionRevision()) {
            return Optional.empty();
        }
        if (motion.revision() == support.motionRevision()) {
            return Optional.of(
                    SupportTransport.resting(
                            intervalTicks,
                            support.motionRevision(),
                            gameTick
                    )
            );
        }

        RigidPose start = motion.poseAt(0.0D);
        if (!poseContinuous(start, support.obstaclePoseAtCapture())) {
            return Optional.empty();
        }

        RigidPose end =
                motion.poseAt(1.0D);

        Vec3d startAnchor =
                support.anchorAt(start);

        Vec3d endAnchor =
                support.anchorAt(end);

        return Optional.of(
                new SupportTransport(
                        endAnchor.subtract(startAnchor),
                        motion.velocityAt(
                                startAnchor,
                                0.0D
                        ),
                        motion.velocityAt(
                                endAnchor,
                                1.0D
                        ),
                        Optional.of(
                                new SupportMotionTrajectory(
                                        motion,
                                        support.localAnchor()
                                )
                        ),
                        intervalTicks,
                        motion.revision(),
                        gameTick
                )
        );
    }

    /**
     * Instantaneous release velocity, independent of transport consumption.
     * A newer continuous publication releases at its start; the publication
     * already captured by support releases at its completed endpoint.
     */
    public static Optional<Vec3d> resolveReleaseVelocity(
            PersistentSupportState support,
            DynamicCollisionObstacleSnapshot obstacle,
            cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext time
    ) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(time, "time");
        RigidTrajectory motion = obstacle.motion();
        if (support.staticSupport()
                || !support.identity().obstacleIdentity().matches(obstacle)
                || motion.tick() != time.gameTick()
                || motion.intervalTicks() != time.intervalTicks()
                || support.gameTick() > time.gameTick()
                || motion.revision() < support.motionRevision()) {
            return Optional.empty();
        }

        double phase = motion.revision() == support.motionRevision()
                ? 1.0D : 0.0D;
        RigidPose releasePose = motion.poseAt(phase);
        if (!poseContinuous(releasePose, support.obstaclePoseAtCapture())) {
            return Optional.empty();
        }
        Vec3d anchor = support.anchorAt(releasePose);
        return Optional.of(motion.velocityAt(anchor, phase));
    }

    private static boolean poseContinuous(
            RigidPose current,
            RigidPose captured
    ) {
        if (current.center().subtract(captured.center()).lengthSquared()
                > POSE_POSITION_TOLERANCE * POSE_POSITION_TOLERANCE) {
            return false;
        }
        Quatd first = BodyOrientation3d.quaternion(
                current.orientation()
        );
        Quatd second = BodyOrientation3d.quaternion(
                captured.orientation()
        );
        return Quatd.angularDistance(first, second)
                <= POSE_ANGLE_TOLERANCE;
    }
}
