package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Character route for persistent support whose material point follows a
 * non-linear rigid trajectory.
 *
 * <p>Pure translation stays on GravityCharacterRoute's normal fast path.
 * Rotation is adaptively linearized into contiguous operation-time windows;
 * every segment is resolved against the same immutable CollisionScene.</p>
 */
public final class SupportedCharacterRoute {
    private SupportedCharacterRoute() {}

    public static GravityMoveResult resolve(
            CollisionBody body,
            KinematicMoveRequest request,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            StepUpIntent stepIntent,
            SupportTransport transport
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(stepIntent, "stepIntent");
        Objects.requireNonNull(transport, "transport");

        Optional<SupportMotionTrajectory> maybeTrajectory =
                transport.trajectory();

        if (maybeTrajectory.isEmpty()
                || !maybeTrajectory.get().requiresGeneralSweep()) {
            return GravityCharacterRoute.resolve(
                    body,
                    request,
                    frame,
                    scene,
                    context,
                    stepIntent
            );
        }

        SupportMotionTrajectory trajectory =
                maybeTrajectory.get();

        int segments =
                trajectory.requiredSegments();

        int trajectoryBudget =
                context.workTracker()
                        .maxSupportTrajectorySegments();

        if (segments > trajectoryBudget) {
            return trajectoryBudgetFailure(
                    request.actualMovement(),
                    frame
            );
        }

        /*
         * request.actualMovement already contains the endpoint support
         * displacement. Remove that chord first, then re-introduce the support
         * movement segment-by-segment from the actual rigid material-point
         * trajectory.
         */
        Vec3d actorRelativeRequest =
                request.actualMovement()
                        .subtract(
                                transport.displacement()
                        );

        OwnedMotion actorOwnership =
                withoutPositionalTransport(
                        request.ownership(),
                        transport.displacement()
                );

        CollisionBody currentBody =
                body;

        Vec3d resolved =
                Vec3d.ZERO;

        Vec3d recovery =
                Vec3d.ZERO;

        Vec3d locomotion =
                Vec3d.ZERO;

        boolean blockedDown = false;
        boolean blockedUp = false;
        boolean blockedTangent = false;
        boolean supportingDuringMove = false;

        Optional<GravitySupportContact> movementSupport =
                Optional.empty();

        double stepHeight = 0.0D;
        double supportFollowRise = 0.0D;
        boolean stepConsumed = false;

        List<Vec3d> tangentNormals =
                new ArrayList<>();

        GravityMoveResult terminal =
                null;

        SweepTimeWindow full =
                SweepTimeWindow.full(
                        scene.time()
                );

        for (int i = 0; i < segments; i++) {
            double lo =
                    (double) i / segments;

            double hi =
                    (double) (i + 1) / segments;

            double fraction =
                    hi - lo;

            Vec3d supportDelta =
                    trajectory.displacement(
                            lo,
                            hi
                    );

            Vec3d actorDelta =
                    actorRelativeRequest
                            .multiply(fraction);

            Vec3d segmentRequest =
                    actorDelta.add(
                            supportDelta
                    );

            OwnedMotion segmentOwnership =
                    actorOwnership
                            .scale(fraction)
                            .add(
                                    new OwnedMotion(
                                            Vec3d.ZERO,
                                            Vec3d.ZERO,
                                            Vec3d.ZERO,
                                            supportDelta
                                    )
                            );

            KinematicMoveRequest segment =
                    new KinematicMoveRequest(
                            segmentRequest,
                            segmentOwnership,
                            request.channel()
                    );

            GravityMoveResult part =
                    GravityCharacterRoute.resolveWindow(
                            currentBody,
                            segment,
                            frame,
                            scene,
                            context,
                            stepConsumed
                                    ? StepUpIntent.disabled()
                                    : stepIntent,
                            full.segment(
                                    lo,
                                    hi
                            )
                    );

            resolved =
                    resolved.add(
                            part.resolvedMovement()
                    );

            recovery =
                    recovery.add(
                            part.recoveryMovement()
                    );

            locomotion =
                    locomotion.add(
                            part.locomotionMovement()
                    );

            blockedDown |=
                    part.blockedDown();

            blockedUp |=
                    part.blockedUp();

            blockedTangent |=
                    part.blockedTangent();

            supportingDuringMove |=
                    part.supportingContactDuringMove();

            if (movementSupport.isEmpty()
                    && part.movementSupportContact()
                    .isPresent()) {
                movementSupport =
                        part.movementSupportContact();
            }

            tangentNormals.addAll(
                    part.tangentBlockingNormals()
            );

            supportFollowRise +=
                    part.supportFollowRise();

            stepHeight +=
                    part.stepHeight();

            stepConsumed |=
                    part.stepHeight()
                            > GravityCharacterRoute
                            .STEP_HEIGHT_EPSILON;

            currentBody =
                    currentBody.move(
                            part.resolvedMovement()
                    );

            terminal =
                    part;

            if (part.indeterminate()) {
                return new GravityMoveResult(
                        request.actualMovement(),
                        resolved,
                        recovery,
                        locomotion,
                        blockedDown,
                        blockedUp,
                        blockedTangent,
                        false,
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        stepHeight,
                        true,
                        tangentNormals,
                        supportFollowRise,
                        frame,
                        Optional.empty(),
                        List.of(),
                        part.indeterminateReason()
                );
            }
        }

        if (terminal == null) {
            return GravityCharacterRoute.resolve(
                    body,
                    request,
                    frame,
                    scene,
                    context,
                    stepIntent
            );
        }

        /*
         * Endpoint truth belongs exclusively to the final time segment.
         * Earlier segments contribute only path-contact / blocking evidence.
         */
        return new GravityMoveResult(
                request.actualMovement(),
                resolved,
                recovery,
                locomotion,
                blockedDown,
                blockedUp,
                blockedTangent,
                supportingDuringMove,
                movementSupport,
                terminal.terminalGrounded(),
                terminal.walkableGround(),
                terminal.supportBlock(),
                stepHeight,
                false,
                tangentNormals,
                supportFollowRise,
                frame,
                terminal.supportContact(),
                terminal.contactVelocityConstraints(),
                MovementIndeterminateReason.NONE
        );
    }

    private static OwnedMotion withoutPositionalTransport(
            OwnedMotion ownership,
            Vec3d positionalTransport
    ) {
        Vec3d remainingSupport =
                ownership.supportMotion()
                        .subtract(
                                positionalTransport
                        );

        return new OwnedMotion(
                ownership.selfWalk(),
                ownership.externalPush(),
                ownership.passive(),
                remainingSupport
        );
    }

    private static GravityMoveResult trajectoryBudgetFailure(
            Vec3d requested,
            GravityFrame frame
    ) {
        return new GravityMoveResult(
                requested,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                false,
                false,
                false,
                false,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                0.0D,
                true,
                List.of(),
                0.0D,
                frame,
                Optional.empty(),
                List.of(),
                MovementIndeterminateReason
                        .SUPPORT_TRAJECTORY_BUDGET
        );
    }
}
