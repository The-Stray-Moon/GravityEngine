package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only, operation-scoped geometry view.
 *
 * <p>Every consumer inside one outer gravity operation queries this interface
 * instead of re-reading {@code Level}/{@code BlockState}/{@code VoxelShape}
 * directly: the movement solve ({@link GravityCharacterRoute}),
 * operation-start collision baselines, the ground probe ({@link GravityGroundProbe}), and
 * geometry-transition pose legality. The production implementation captures
 * Minecraft world geometry once per operation and reuses it for every cheap
 * subquery.</p>
 *
 * <p>Rigid moving structures enter through immutable local geometry and
 * completed motion publications transformed by poseAt(t). Character runtime must not own the moving world through persistent
 * support state.</p>
 */
public interface CollisionScene {
    /**
     * Returns the deterministic obstacle snapshot covering the swept corridor
     * for the explicit operation-local {@link SweepTimeWindow}.
     *
     * <p>Moving obstacles must be broad-phased from their position at
     * {@code window.startTicks()} over their movement during
     * {@code window.durationTicks()}; a multi-bump solver must never re-query
     * an implicit full-tick interval for a later bump.</p>
     */
    List<CollisionObstacle> query(
            CollisionBody body,
            Vector3dc movement,
            SweepTimeWindow window
    );

    /**
     * Support-classification view of the already captured immutable scene.
     *
     * <p>This performs the same deterministic geometric filtering as
     * {@link #query(CollisionBody, Vector3dc, SweepTimeWindow)}, but support
     * classification owns an independent narrow-phase/work budget. Hard movement
     * legality must not become indeterminate merely because a secondary foot
     * support classification budget was exhausted.</p>
     *
     * <p>Frozen test scenes may delegate to the ordinary query. Production
     * captured scenes override this method so repeated support filtering does not
     * consume the hard-collision obstacle budget.</p>
     */
    default List<CollisionObstacle> querySupport(
            CollisionBody body,
            Vector3dc movement,
            SweepTimeWindow window
    ) {
        return query(body, movement, window);
    }

    /**
     * Default full-operation query.
     *
     * <p>Permitted only for the operation's initial full-interval query and
     * focused frozen-scene tests. Multi-bump solvers and segment allocators must pass
     * their current {@link SweepTimeWindow} explicitly.</p>
     */
    default List<CollisionObstacle> query(
            CollisionBody body,
            Vector3dc movement
    ) {
        return query(
                body,
                movement,
                SweepTimeWindow.full(time())
        );
    }

    /**
     * Pose-fit obstacle materialization for one exact candidate body.
     *
     * <p>Production scenes capture pose-strict ordinary-entity snapshots at
     * the capture boundary; this default (used by frozen test scenes without
     * pose metadata) returns the current-instant static/hard-dynamic
     * obstacles at operation time zero.</p>
     */
    default List<CollisionObstacle> queryPoseFit(CollisionBody body) {
        Objects.requireNonNull(body, "body");
        return query(
                body,
                new Vector3d(),
                new SweepTimeWindow(0.0D, 0.0D)
        );
    }

    /**
     * Immutable movement material evidence for a captured block position.
     *
     * <p>Production scenes evaluate friction/speed-factor/fluid
     * classification once at the capture boundary; frozen test scenes
     * without material metadata return empty (vanilla default material).
     * Movement policy must never read a live {@code BlockState}/{@code Block}.
     * Empty is a proven in-scene "no material" answer; out-of-domain lookups
     * are {@link CollisionSceneCoverageException}s.</p>
     */
    default Optional<BlockMovementMaterialSnapshot> movementMaterialAt(
            BlockPos position
    ) {
        return Optional.empty();
    }

    /** Game tick the scene was created for; used to reject stale dynamic snapshots. */
    default long tick() {
        return -1L;
    }

    /** The explicit simulation-time contract for this operation. */
    default KinematicStepContext time() {
        return new KinematicStepContext(tick(), 1.0D, revision());
    }

    /** Operation-local scene revision for diagnostics/lifecycle sanity. */
    default long revision() {
        return 0L;
    }

    /** Operation-local work diagnostics for this scene. */
    default CollisionWorkDiagnostics diagnostics() {
        return CollisionWorkDiagnostics.empty();
    }
}
