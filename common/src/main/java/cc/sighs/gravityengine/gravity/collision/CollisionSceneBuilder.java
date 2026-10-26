package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Operation-scoped accumulator for engine-owned dynamic collision geometry.
 *
 * <p>One builder is created for one outer operation capture. External rigid
 * providers add immutable {@link DynamicCollisionObstacleSnapshot}s; the
 * resulting scene is frozen once and then consumed by the ordinary collision
 * solve. The builder neither reads nor retains a Minecraft world object.</p>
 *
 * <p>When the builder is given the operation's {@link KinematicStepContext},
 * {@link #build()} validates tick, interval, primitive identity and conservative
 * material-point reach exactly like a provider publication captured directly
 * from an entity.</p>
 */
public final class CollisionSceneBuilder
        implements RigidPublicationCollector {
    private final KinematicStepContext time;
    private final List<DynamicCollisionObstacleSnapshot> obstacles =
            new ArrayList<>();

    /**
     * Creates a context-free builder.
     *
     * <p>This form is useful for focused construction/tests. Production
     * capture should pass the operation's exact
     * {@link KinematicStepContext} so rigid publications are validated
     * against the same simulation interval the solver will consume.</p>
     */
    public CollisionSceneBuilder() {
        this(null);
    }

    /** Creates a builder bound to one operation's exact simulation time. */
    public CollisionSceneBuilder(KinematicStepContext time) {
        this.time = time;
    }

    /** Adds one immutable rigid-obstacle publication. */
    @Override
    public void addObstacle(
            DynamicCollisionObstacleSnapshot obstacle
    ) {
        if (obstacle == null) {
            throw new IllegalArgumentException(
                    "obstacle cannot be null"
            );
        }
        this.obstacles.add(obstacle);
    }

    /** Immutable snapshot of the obstacles added so far. */
    public List<DynamicCollisionObstacleSnapshot> obstacles() {
        return List.copyOf(this.obstacles);
    }

    /**
     * Freezes the accumulated dynamic obstacles into a scene view.
     *
     * <p>The returned scene is immutable and can be queried repeatedly. The
     * builder remains usable for diagnostics but no later mutation can change
     * the returned scene.</p>
     */
    public CollisionScene build() {
        List<DynamicCollisionObstacleSnapshot> captured =
                List.copyOf(this.obstacles);
        KinematicStepContext context =
                this.time != null
                        ? this.time
                        : KinematicStepContext.fullTick(
                                0L,
                                0L
                        );

        if (this.time != null) {
            DynamicEntityBroadphasePolicy.validateCaptured(
                    captured,
                    context
            );
        }

        return new DynamicObstacleScene(
                captured,
                context
        );
    }

    /**
     * Immutable dynamic-only scene used to transfer one capture result without
     * exposing the builder's mutable list.
     */
    private static final class DynamicObstacleScene
            implements CollisionScene {
        private final List<DynamicCollisionObstacleSnapshot> obstacles;
        private final KinematicStepContext time;

        private DynamicObstacleScene(
                List<DynamicCollisionObstacleSnapshot> obstacles,
                KinematicStepContext time
        ) {
            this.obstacles = List.copyOf(obstacles);
            this.time = Objects.requireNonNull(time, "time");
        }

        @Override
        public List<CollisionObstacle> query(
                CollisionBody body,
                Vec3d movement,
                SweepTimeWindow window
        ) {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(window, "window");

            Aabb3d searchBox =
                    body.rawSweptAabb(movement)
                            .inflate(CollisionTolerances.CONTACT_SLOP)
                            .inflate(CollisionTolerances.CONTACT_SKIN);

            double intervalTicks =
                    this.time.intervalTicks();
            double normalizedStart =
                    window.normalizedStartTicks(
                            intervalTicks
                    );
            double normalizedEnd =
                    Math.min(
                            1.0D,
                            normalizedStart
                                    + window.normalizedDurationTicks(
                                    intervalTicks
                            )
                    );

            List<CollisionObstacle> result =
                    new ArrayList<>();

            for (DynamicCollisionObstacleSnapshot obstacle
                    : this.obstacles) {
                if (obstacle.sweptBounds(
                                normalizedStart,
                                normalizedEnd)
                        .inflate(
                                CollisionTolerances.CONTACT_SKIN)
                        .intersects(searchBox)) {
                    result.add(new EntityObstacle(obstacle));
                }
            }

            result.sort(CollisionObstacle.STABLE_COMPARATOR);
            return List.copyOf(result);
        }

        @Override
        public List<DynamicCollisionObstacleSnapshot> dynamicObstacles() {
            return this.obstacles;
        }

        @Override
        public long tick() {
            return this.time.gameTick();
        }

        @Override
        public KinematicStepContext time() {
            return this.time;
        }

        @Override
        public long revision() {
            return this.time.sceneRevision();
        }
    }
}
