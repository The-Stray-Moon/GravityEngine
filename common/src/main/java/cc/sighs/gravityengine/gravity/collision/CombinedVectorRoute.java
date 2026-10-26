package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Combined-vector route for passive vanilla-Aabb3d entities.
 *
 * <p>This route deliberately has no feet anchor, step, slope traversal,
 * locomotion ownership, jump, or character ground state. It reuses only the
 * shape-neutral narrow phase, recovery and convex projector kernels.</p>
 */
public final class CombinedVectorRoute {
    private static final int MAX_BUMPS = 4;

    private CombinedVectorRoute() {}

    public static PassiveGravityMoveResult resolve(
            CollisionBody initialBody,
            Vec3d requestedMovement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context
    ) {
        return resolve(initialBody, requestedMovement, frame, scene, context, body -> frame);
    }

    /** Endpoint classification is independent of the frozen integration frame and never moves the body. */
    public static PassiveGravityMoveResult resolve(CollisionBody initialBody, Vec3d requestedMovement,
            GravityFrame frame, CollisionScene scene, ObbQueryContext context,
            java.util.function.Function<CollisionBody, GravityFrame> endpointFrameResolver) {
        Objects.requireNonNull(initialBody, "initialBody");
        Objects.requireNonNull(requestedMovement, "requestedMovement");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");

        CollisionRecovery.RecoveryResult recovery;
        try {
            SweepTimeWindow recoveryInstant =
                    SweepTimeWindow.full(scene.time())
                            .instantAtStart();
            recovery = CollisionRecovery.recover(
                    initialBody,
                    (body, movement) -> scene.query(
                            body,
                            movement,
                            recoveryInstant
                    ),
                    context
            );
        } catch (CollisionComplexityLimitException ex) {
            return degraded(
                    requestedMovement,
                    CollisionRecovery.RecoveryResult.noRecovery(initialBody),
                    frame
            );
        }
        if (!recovery.recovered()) {
            /*
             * Recovery may have moved the body part of the way out of its
             * initial overlap. That partial correction is proven geometry, so
             * it is preserved instead of rewinding the whole movement to zero.
             * A recovery failure no longer traps the entity in place.
             */
            return degraded(
                    requestedMovement,
                    recovery,
                    Vec3d.ZERO,
                    List.of(),
                    frame
            );
        }

        KinematicSweepKernel.HomogeneousAdvanceResult advance =
                KinematicSweepKernel.advanceHomogeneous(
                        recovery.recoveredBody(),
                        requestedMovement,
                        scene,
                        SweepTimeWindow.full(scene.time()),
                        context,
                        MAX_BUMPS);
        CollisionBody body = advance.body();
        Vec3d locomotion = advance.locomotion();
        List<Vec3d> blockingNormals = new ArrayList<>(advance.blockingNormals());
        boolean indeterminate = advance.indeterminate();
        if (!indeterminate && hasMeaningfulPenetration(
                body, scene, context
        )) {
            indeterminate = true;
        }
        if (indeterminate) {
            /*
             * Keep the locomotion already proven by completed advance
             * segments; only the unproven remainder is unknown.
             */
            return degraded(
                    requestedMovement,
                    recovery,
                    locomotion,
                    blockingNormals,
                    frame
            );
        }

        Vec3d up = frameUp(frame);
        Vec3d down = frameDown(frame);
        GravityFrame endpointFrame = Objects.requireNonNull(endpointFrameResolver.apply(body));
        Support support = supportAt(
                body,
                endpointFrame,
                scene,
                scene.time().intervalTicks(),
                context
        );
        if (support.indeterminate()) {
            /*
             * Support is a derived fact. Its uncertainty suppresses support
             * publication but cannot erase the proven translation.
             */
            return degraded(
                    requestedMovement,
                    recovery,
                    locomotion,
                    blockingNormals,
                    frame
            );
        }

        boolean blockedDown = blockingNormals.stream().anyMatch(
                normal -> isSupportNormal(normal, frame)
                        && requestedMovement.dot(normal)
                        < -CollisionTolerances.ENTERING_PLANE_EPSILON
        );
        boolean blockedUp = blockingNormals.stream().anyMatch(
                normal -> normal.dot(down)
                        >= TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT
                        && requestedMovement.dot(normal)
                        < -CollisionTolerances.ENTERING_PLANE_EPSILON
        );
        boolean blockedTangent = blockingNormals.stream().anyMatch(
                normal -> !isSupportNormal(normal, frame)
                        && normal.dot(down)
                        < TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT
        );

        return new PassiveGravityMoveResult(
                requestedMovement,
                recovery.recoveryMovement().add(locomotion),
                recovery.recoveryMovement(),
                blockingNormals,
                blockedDown,
                blockedUp,
                blockedTangent,
                support.normal().isPresent(),
                support.normal(),
                support.block(),
                false,
                frame,
                advance.impacts(),
                support.contact(),
                endpointFrame
        );
    }

    public static Vec3d projectVelocity(
            Vec3d velocity,
            List<? extends Vec3d> blockingNormals
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(blockingNormals, "blockingNormals");

        List<ContactConstraintProjector.Constraint> constraints =
                blockingNormals.stream()
                        .map(normal ->
                                new ContactConstraintProjector.Constraint(
                                        normal,
                                        0.0D
                                ))
                        .toList();
        ContactConstraintProjector.Result projection =
                ContactConstraintProjector.project(
                        velocity,
                        constraints
                );
        if (projection.infeasible()) {
            // Homogeneous constraints (normal dot v >= 0) always admit the
            // zero vector, so infeasibility is a contract bug in the exported
            // normal set. A velocity response must never mask it with a
            // fabricated vector.
            throw new IllegalStateException(
                    "homogeneous contact constraints unexpectedly "
                            + "infeasible: normals="
                            + blockingNormals
            );
        }
        return projection.requireProjectedVector();
    }

    public static boolean isSupportNormal(Vec3d normal, GravityFrame frame) {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(frame, "frame");
        return normal.dot(frameUp(frame))
                >= TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT;
    }

    private static Support supportAt(
            CollisionBody body,
            GravityFrame frame,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        if (frame.strength() == 0) return Support.NONE;
        Vec3d probe = frameDown(frame).multiply(GravityGroundProbe.PROBE_DISTANCE);
        /*
         * Support is a current-instant question: only geometry that is
         * physically beneath the final body at this obstacle time may count.
         * A platform that merely moves into probe range later in the tick is
         * not current support, so the scene query uses a zero-duration window
         * at the operation's current elapsed time and the narrow phase probes
         * the obstacle at that same instant.
         */
        CurrentContactQuery.Result current =
                CurrentContactQuery.query(
                        body,
                        probe,
                        scene,
                        obstacleTimeTicks,
                        context
                );
        if (current.indeterminate()) {
            return Support.INDETERMINATE;
        }
        CollisionContact best = null;
        for (CollisionContact contact : current.contacts()) {
            if (!isSupportNormal(contact.normal(), frame)) continue;
            if (best == null
                    || KinematicSweepKernel.CONTACT_ORDER.compare(
                            contact, best) < 0) {
                best = contact;
            }
        }
        if (best == null) return Support.NONE;
        Optional<CellPos> block = best.obstacle() instanceof BlockObstacle obstacle
                ? Optional.of(obstacle.blockPos()) : Optional.empty();
        var contact = materialContact(best);
        if (best.obstacle() instanceof BlockObstacle obstacle && GravitySupportContact.blockFaceIndex(best.normal())>=0) {
            // SAT's representative midpoint is not a material anchor. The current exact contact
            // selects a real finite face; reconstruct its material point before retaining it.
            contact = new GravitySupportContact(best.normal(), best.surfaceVelocity(),
                    GravitySupportContact.blockFaceWitness(obstacle.bounds(), best.normal(), best.point()),
                    GravitySupportContact.SupportGeometryKind.TRUSTED_CURRENT_BLOCK_FACE, contact.faceIdentity());
        }
        return new Support(Optional.of(best.normal()), block, false, Optional.of(contact));
    }

    /** Primitive address for invocation-local material lookup, never a reusable plane witness. */
    public static GravitySupportContact materialContact(CollisionContact contact) {
        var identity = contact.obstacle() instanceof BlockObstacle block
                ? new SupportFaceIdentity(block.blockPos(), block.bounds(), -1,
                    GravitySupportContact.blockFaceIndex(contact.normal())) : null;
        return new GravitySupportContact(contact.normal(), contact.surfaceVelocity(), contact.point(),
                GravitySupportContact.SupportGeometryKind.SWEEP_CONTACT, identity);
    }

    private static boolean hasMeaningfulPenetration(
            CollisionBody body,
            CollisionScene scene,
            ObbQueryContext context
    ) {
        CurrentContactQuery.Result current =
                CurrentContactQuery.contacts(
                        body,
                        scene,
                        scene.time().intervalTicks(),
                        context
                );
        return CurrentContactQuery.hasMeaningfulPenetration(current);
    }

    private static void addNormal(List<Vec3d> normals, Vec3d normal) {
        if (normals.stream().noneMatch(existing ->
                CollisionTolerances.sameConstraintIdentity(existing, normal))) {
            normals.add(normal);
        }
    }

    /**
     * Degraded passive result carrying the recovery displacement proven so
     * far and no locomotion. Zero locomotion here means the advance never
     * proved a segment, not that a proven displacement was rewound.
     */
    private static PassiveGravityMoveResult degraded(
            Vec3d requestedMovement,
            CollisionRecovery.RecoveryResult recovery,
            GravityFrame frame
    ) {
        return degraded(
                requestedMovement,
                recovery,
                Vec3d.ZERO,
                List.of(),
                frame
        );
    }

    /** Degraded result carrying every displacement already proven. */
    private static PassiveGravityMoveResult degraded(
            Vec3d requestedMovement,
            CollisionRecovery.RecoveryResult recovery,
            Vec3d locomotion,
            List<Vec3d> blockingNormals,
            GravityFrame frame
    ) {
        Vec3d recoveryMovement = recovery.recoveryMovement();
        return new PassiveGravityMoveResult(
                requestedMovement,
                recoveryMovement.add(locomotion),
                recoveryMovement,
                List.copyOf(blockingNormals),
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                true,
                frame
        );
    }

    /** Neutral canonical gravity-up axis from the immutable frame orientation. */
    static Vec3d frameUp(GravityFrame frame) {
        return frame.orientation().axisY();
    }

    /** Neutral canonical gravity-down axis from the immutable frame orientation. */
    static Vec3d frameDown(GravityFrame frame) {
        return frame.orientation().axisY().negate();
    }

    private record Support(
            Optional<Vec3d> normal,
            Optional<CellPos> block,
            boolean indeterminate,
            Optional<GravitySupportContact> contact
    ) {
        private static final Support NONE =
                new Support(Optional.empty(), Optional.empty(), false, Optional.empty());
        private static final Support INDETERMINATE =
                new Support(Optional.empty(), Optional.empty(), true, Optional.empty());
    }
}
