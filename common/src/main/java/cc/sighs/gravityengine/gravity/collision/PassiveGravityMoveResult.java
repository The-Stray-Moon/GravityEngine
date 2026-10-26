package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of one passive arbitrary-gravity collision solve.
 */
public record PassiveGravityMoveResult(
        Vec3d requestedMovement,
        Vec3d appliedMovement,
        Vec3d recoveryMovement,
        List<Vec3d> blockingNormals,
        boolean blockedDown,
        boolean blockedUp,
        boolean blockedTangent,
        boolean supported,
        Optional<Vec3d> supportNormal,
        Optional<CellPos> supportBlock,
        boolean indeterminate,
        GravityFrame frame,
        List<CollisionContact> impacts,
        Optional<GravitySupportContact> endpointContact,
        GravityFrame endpointFrame
) {
    public PassiveGravityMoveResult(Vec3d requestedMovement, Vec3d appliedMovement, Vec3d recoveryMovement,
            List<Vec3d> blockingNormals, boolean blockedDown, boolean blockedUp, boolean blockedTangent,
            boolean supported, Optional<Vec3d> supportNormal, Optional<CellPos> supportBlock,
            boolean indeterminate, GravityFrame frame) {
        this(requestedMovement, appliedMovement, recoveryMovement, blockingNormals, blockedDown, blockedUp,
                blockedTangent, supported, supportNormal, supportBlock, indeterminate, frame,
                List.of(), Optional.empty(), frame);
    }

    public PassiveGravityMoveResult withEndpoint(Optional<GravitySupportContact> contact, GravityFrame terminalFrame) {
        return new PassiveGravityMoveResult(requestedMovement, appliedMovement, recoveryMovement, blockingNormals,
                blockedDown, blockedUp, blockedTangent, contact.isPresent(), contact.map(GravitySupportContact::normal),
                contact.map(GravitySupportContact::faceIdentity).map(SupportFaceIdentity::block), indeterminate, frame,
                impacts, contact, terminalFrame);
    }

    public PassiveGravityMoveResult {
        impacts = List.copyOf(impacts);
        Objects.requireNonNull(endpointContact, "endpointContact");
        Objects.requireNonNull(endpointFrame, "endpointFrame");
        Objects.requireNonNull(
                requestedMovement,
                "requestedMovement"
        );
        Objects.requireNonNull(
                appliedMovement,
                "appliedMovement"
        );
        Objects.requireNonNull(
                recoveryMovement,
                "recoveryMovement"
        );
        Objects.requireNonNull(
                blockingNormals,
                "blockingNormals"
        );
        Objects.requireNonNull(
                supportNormal,
                "supportNormal"
        );
        Objects.requireNonNull(
                supportBlock,
                "supportBlock"
        );
        Objects.requireNonNull(
                frame,
                "frame"
        );

        requireFinite(
                requestedMovement,
                "requestedMovement"
        );
        requireFinite(
                appliedMovement,
                "appliedMovement"
        );
        requireFinite(
                recoveryMovement,
                "recoveryMovement"
        );

        blockingNormals = blockingNormals.stream()
                .map(normal -> {
                    Objects.requireNonNull(
                            normal,
                            "blocking normal"
                    );
                    requireNormalized(normal);
                    return normal;
                })
                .toList();

        supportNormal = supportNormal.map(normal -> {
            Objects.requireNonNull(
                    normal,
                    "support normal"
            );
            requireNormalized(normal);
            return normal;
        });

        supportBlock = Objects.requireNonNull(supportBlock, "supportBlock");

        if (supported != supportNormal.isPresent()) {
            throw new IllegalArgumentException(
                    "supported must match supportNormal presence"
            );
        }
    }

    @Override
    public List<Vec3d> blockingNormals() {
        return List.copyOf(blockingNormals);
    }

    /**
     * Passive collision never owns stair traversal.
     */
    public StepUpIntent stepIntent() {
        return StepUpIntent.disabled();
    }

    private static void requireFinite(
            Vec3d vector,
            String name
    ) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }

    private static void requireNormalized(
            Vec3d normal
    ) {
        requireFinite(normal, "normal");

        if (Math.abs(normal.lengthSquared() - 1.0D)
                > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "normal must be normalized: " + normal
            );
        }
    }
}
