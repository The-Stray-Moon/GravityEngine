package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.collision.CollisionTolerances;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.collision.RigidOccupancyCapture;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Objects;

/**
 * One immutable dynamic-rigid snapshot for one logical packet validation.
 *
 * <p>The snapshot is captured once and consumed by both old-body and new-body
 * occupancy tests. It contains no block voxel geometry, world-border geometry,
 * ordinary entity pose-fit operands or live provider handle.</p>
 *
 * <p>Thread ownership: this is a {@link ServerPlayer} packet-validation
 * facility. A live world/provider capture is only legal on the server thread
 * that owns the player's {@link ServerLevel}; the packet listener owns packet
 * scheduling through Vanilla, so this helper never reschedules itself.</p>
 *
 * <p>Work ownership: the snapshot carries the bounded capture outcome and the
 * operation tracker shared by the two occupancy predicates. A budget-exhausted
 * snapshot is an explicit indeterminate state and its predicates fail
 * closed.</p>
 */
public final class RigidOccupancySnapshot {
    private final long gameTick;
    private final long revision;
    private final KinematicStepContext time;
    private final List<DynamicCollisionObstacleSnapshot> obstacles;
    private final List<CollisionBody> endpointBodies;
    private final boolean budgetExhausted;
    private final CollisionWorkTracker tracker;

    public RigidOccupancySnapshot(
            long gameTick,
            long revision,
            KinematicStepContext time,
            List<DynamicCollisionObstacleSnapshot> obstacles,
            boolean budgetExhausted,
            CollisionWorkTracker tracker
    ) {
        if (gameTick < 0L) {
            throw new IllegalArgumentException("gameTick must be non-negative");
        }
        this.gameTick = gameTick;
        this.revision = revision;
        this.time = Objects.requireNonNull(time, "time");
        this.obstacles = List.copyOf(obstacles);
        this.budgetExhausted = budgetExhausted;
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        // Convert each frozen publication to its endpoint geometry once.
        var bodies = new java.util.ArrayList<CollisionBody>(this.obstacles.size());
        for (var obstacle : this.obstacles) {
            bodies.add(obstacle.exactBodyAt(1.0D));
        }
        this.endpointBodies = List.copyOf(bodies);
    }

    public long gameTick() { return gameTick; }
    public long revision() { return revision; }
    public KinematicStepContext time() { return time; }
    public List<DynamicCollisionObstacleSnapshot> obstacles() { return obstacles; }
    public boolean budgetExhausted() { return budgetExhausted; }
    public CollisionWorkTracker tracker() { return tracker; }

    /** Prepared terminal bodies in the same order as the frozen publications. */
    List<CollisionBody> endpointBodies() { return endpointBodies; }

    /**
     * Convenience construction for already-complete captures (tests and
     * focused fixtures). The default budget state is complete.
     */
    public RigidOccupancySnapshot(
            long gameTick,
            long revision,
            KinematicStepContext time,
            List<DynamicCollisionObstacleSnapshot> obstacles
    ) {
        this(
                gameTick,
                revision,
                time,
                obstacles,
                false,
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                )
        );
    }

    /**
     * Captures the dynamic rigid operands once for both packet occupancy
     * predicates.
     *
     * <p>Must run on the server thread that owns {@code collisionOwner}'s
     * level. Called from the network thread (before Vanilla's own
     * {@code ensureRunningOnSameThread} hand-off) this fails fast rather than
     * reading live world/provider state off-thread.</p>
     */
    public static RigidOccupancySnapshot capture(
            ServerPlayer collisionOwner,
            CollisionBody oldBody,
            CollisionBody requestedBody
    ) {
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(oldBody, "oldBody");
        Objects.requireNonNull(requestedBody, "requestedBody");

        ServerLevel level = collisionOwner.serverLevel();
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException(
                    "RigidOccupancySnapshot.capture must run on the "
                            + "owning server thread"
            );
        }

        long tick = level.getGameTime();
        long revision = 0L;
        KinematicStepContext time =
                KinematicStepContext.fullTick(tick, revision);
        Aabb3d queryBounds = oldBody.enclosingAabb()
                .union(requestedBody.enclosingAabb())
                .inflate(CollisionTolerances.CONTACT_SKIN);

        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                );
        RigidOccupancyCapture capture =
                MinecraftCollisionSceneCapture
                        .captureRigidOccupancyBounded(
                                collisionOwner,
                                queryBounds,
                                time,
                                tracker
                        );

        return new RigidOccupancySnapshot(
                tick,
                revision,
                time,
                capture.obstacles(),
                capture.budgetExhausted(),
                tracker
        );
    }

    public boolean empty() {
        return obstacles.isEmpty();
    }

    /**
     * Whether the packet occupancy result is indeterminate because the rigid
     * work budget was exhausted during capture or narrow-phase testing.
     */
    public boolean indeterminate() {
        return budgetExhausted || tracker.limitExceeded();
    }

    /**
     * Charges one narrow-phase test against this snapshot's operation budget.
     *
     * @return {@code false} when the budget is exhausted and the caller must
     *         fail closed
     */
    public boolean chargeNarrowPhaseTest() {
        return !budgetExhausted
                && tracker.recordNarrowPhaseTest();
    }
}
