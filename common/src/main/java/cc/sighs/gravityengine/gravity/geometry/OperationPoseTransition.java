package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.KinematicPose;

import java.util.Objects;

/**
 * Immutable result of geometry-transition planning.
 *
 * <p>Planning evaluates candidates against the operation's already-captured
 * scene and never writes to an entity.</p>
 *
 * @param selectedFrame     frame whose collision axis is currently installed;
 *                          equals {@code candidatePose.frame()} whenever a
 *                          geometry commit is planned
 * @param candidatePose     pose to install, or {@code null} when the installed
 *                          representation is retained
 * @param kind              which anchor policy produced the decision
 * @param positionCorrection P displacement the candidate requires, zero when
 *                          no commit is planned
 * @param rejectionReason   stable diagnostic reason for the first candidate
 *                          that failed, or {@code null} on success
 * @param geometryChanged   whether the commit rebuilds the exact body
 * @param diagnostics       candidate-fit and support evidence for diagnostics
 */
public record OperationPoseTransition(
        GravityFrame selectedFrame,
        KinematicPose candidatePose,
        GeometryTransitionKind kind,
        Vec3d positionCorrection,
        String rejectionReason,
        boolean geometryChanged,
        OperationFrameDiagnostics diagnostics
) {
    public OperationPoseTransition {
        Objects.requireNonNull(selectedFrame, "selectedFrame");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(positionCorrection, "positionCorrection");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (kind == GeometryTransitionKind.DEFERRED
                || kind == GeometryTransitionKind.UNCHANGED) {
            if (candidatePose != null) {
                throw new IllegalArgumentException(
                        kind + " transition cannot plan a pose commit"
                );
            }
        } else if (candidatePose == null) {
            throw new IllegalArgumentException(
                    kind + " transition must carry the pose it installs"
            );
        }
    }

    public boolean installsPose() {
        return this.kind != GeometryTransitionKind.UNCHANGED
                && this.kind != GeometryTransitionKind.DEFERRED;
    }

    public OperationFrameResult toFrameResult() {
        if (this.kind == GeometryTransitionKind.UNCHANGED) {
            return new OperationFrameResult(
                    this.selectedFrame,
                    GeometryTransitionStatus.UNCHANGED,
                    false,
                    Vec3d.ZERO,
                    GeometryTransitionKind.UNCHANGED
            );
        }
        if (this.kind == GeometryTransitionKind.DEFERRED) {
            return new OperationFrameResult(
                    this.selectedFrame,
                    GeometryTransitionStatus.DEFERRED,
                    false,
                    Vec3d.ZERO,
                    GeometryTransitionKind.DEFERRED
            );
        }
        return new OperationFrameResult(
                this.selectedFrame,
                GeometryTransitionStatus.APPLIED,
                this.geometryChanged,
                this.positionCorrection,
                this.kind
        );
    }
}
