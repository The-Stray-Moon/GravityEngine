package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of one passive arbitrary-gravity collision solve.
 */
public record PassiveGravityMoveResult(
        Vector3d requestedMovement,
        Vector3d appliedMovement,
        Vector3d recoveryMovement,
        List<Vector3d> blockingNormals,
        boolean blockedDown,
        boolean blockedUp,
        boolean blockedTangent,
        boolean supported,
        Optional<Vector3d> supportNormal,
        Optional<BlockPos> supportBlock,
        boolean indeterminate,
        GravityFrame frame
) {
    public PassiveGravityMoveResult {
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

        requestedMovement =
                new Vector3d(requestedMovement);
        appliedMovement =
                new Vector3d(appliedMovement);
        recoveryMovement =
                new Vector3d(recoveryMovement);

        blockingNormals = blockingNormals.stream()
                .map(normal -> {
                    Objects.requireNonNull(
                            normal,
                            "blocking normal"
                    );
                    requireNormalized(normal);
                    return new Vector3d(normal);
                })
                .toList();

        supportNormal = supportNormal.map(normal -> {
            Objects.requireNonNull(
                    normal,
                    "support normal"
            );
            requireNormalized(normal);
            return new Vector3d(normal);
        });

        supportBlock =
                supportBlock.map(BlockPos::immutable);

        if (supported != supportNormal.isPresent()) {
            throw new IllegalArgumentException(
                    "supported must match supportNormal presence"
            );
        }
    }

    @Override
    public Vector3d requestedMovement() {
        return new Vector3d(requestedMovement);
    }

    @Override
    public Vector3d appliedMovement() {
        return new Vector3d(appliedMovement);
    }

    @Override
    public Vector3d recoveryMovement() {
        return new Vector3d(recoveryMovement);
    }

    @Override
    public List<Vector3d> blockingNormals() {
        return blockingNormals.stream()
                .map(Vector3d::new)
                .toList();
    }

    @Override
    public Optional<Vector3d> supportNormal() {
        return supportNormal.map(Vector3d::new);
    }

    /**
     * Passive collision never owns stair traversal.
     */
    public StepUpIntent stepIntent() {
        return StepUpIntent.disabled();
    }

    private static void requireFinite(
            Vector3d vector,
            String name
    ) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }

    private static void requireNormalized(
            Vector3d normal
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